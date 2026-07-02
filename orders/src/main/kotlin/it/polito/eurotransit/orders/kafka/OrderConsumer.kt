package it.polito.eurotransit.orders.kafka

import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.client.PaymentRequest
import it.polito.eurotransit.orders.domain.ProcessedEvent
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

// incoming events
data class InventoryReservedEvent(
    val eventId: String, val orderId: String, val reservationId: String,
    val trainId: String, val seatClass: String, val quantity: Int
)

data class InventoryReservationFailedEvent(
    val eventId: String, val orderId: String, val reason: String
)

// outgoing events
data class OrderConfirmedEvent(
    val eventId: String, val orderId: String, val userEmail: String,
    val trainId: String, val seatClass: String, val quantity: Int,
    val amount: BigDecimal, val transactionId: String
)

data class OrderFailedEvent(
    val eventId: String, val orderId: String, val reservationId: String?,
    val reason: String, val userEmail: String
)

@Component
class OrderConsumer(
    private val orderRepo: OrderRepository,
    private val eventRepo: ProcessedEventRepository,
    private val paymentClient: PaymentClient,
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    // structured concurrency: scope for kafka consumers tied to this component's lifecycle
    private val consumerJob = SupervisorJob()
    private val consumerScope = CoroutineScope(Dispatchers.IO + consumerJob)

    // graceful shutdown: cooperative cancellation on SIGTERM
    @PreDestroy
    fun shutdown() {
        consumerJob.cancel()
    }

    @KafkaListener(topics = ["eurotransit.inventory-reserved"], groupId = "orders-group")
    fun handleReserved(event: InventoryReservedEvent) {
        // launch coroutine to process the event async but safely structured
        consumerScope.launch {
            if (eventRepo.existsById(event.eventId)) return@launch

            val order = orderRepo.findById(event.orderId) ?: return@launch
            val payReq = PaymentRequest(order.orderId, order.userId, order.amount, order.currency)
            
            // native suspension with circuit breaker
            val payRes = paymentClient.authorizePayment(payReq)
            
            if (payRes.status == "AUTHORIZED") {
                val updated = order.copy(
                    status = "CONFIRMED", 
                    transactionId = payRes.transactionId, 
                    confirmedAt = LocalDateTime.now()
                )
                orderRepo.save(updated)
                
                val confirmedEvent = OrderConfirmedEvent(
                    "evt-${UUID.randomUUID()}", updated.orderId, updated.userEmail,
                    updated.trainId, updated.seatClass, updated.quantity,
                    updated.amount, payRes.transactionId!!
                )
                kafkaTemplate.send("eurotransit.order-confirmed", updated.orderId, confirmedEvent)
            } else {
                val updated = order.copy(status = "FAILED")
                orderRepo.save(updated)
                
                val failedEvent = OrderFailedEvent(
                    "evt-${UUID.randomUUID()}", updated.orderId, event.reservationId,
                    payRes.reason ?: "payment_declined", updated.userEmail
                )
                kafkaTemplate.send("eurotransit.order-failed", updated.orderId, failedEvent)
            }
            
            eventRepo.save(ProcessedEvent(event.eventId))
        }
    }

    @KafkaListener(topics = ["eurotransit.inventory-reservation-failed"], groupId = "orders-group")
    fun handleReservationFailed(event: InventoryReservationFailedEvent) {
        consumerScope.launch {
            if (eventRepo.existsById(event.eventId)) return@launch

            val order = orderRepo.findById(event.orderId) ?: return@launch
            val updated = order.copy(status = "FAILED")
            orderRepo.save(updated)
            
            val failedEvent = OrderFailedEvent(
                "evt-${UUID.randomUUID()}", updated.orderId, null,
                event.reason, updated.userEmail
            )
            kafkaTemplate.send("eurotransit.order-failed", updated.orderId, failedEvent)
            
            eventRepo.save(ProcessedEvent(event.eventId))
        }
    }
}