package it.polito.eurotransit.orders.kafka

import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.client.PaymentRequest
import it.polito.eurotransit.orders.domain.ProcessedEvent
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
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

    // handle successful inventory reservation and trigger payments
    @KafkaListener(topics = ["eurotransit.inventory-reserved"], groupId = "orders-group")
    fun handleReserved(event: InventoryReservedEvent) {
        eventRepo.existsById(event.eventId).flatMap { exists ->
            if (exists) Mono.empty()
            else orderRepo.findById(event.orderId).flatMap { order ->
                
                // create payment request
                val payReq = PaymentRequest(order.orderId, order.userId, order.amount, order.currency)
                
                paymentClient.authorizePayment(payReq).flatMap { payRes ->
                    if (payRes.status == "AUTHORIZED") {
                        // payment ok: confirm order
                        val updated = order.copy(
                            status = "CONFIRMED", 
                            transactionId = payRes.transactionId, 
                            confirmedAt = LocalDateTime.now()
                        )
                        orderRepo.save(updated).doOnSuccess { saved ->
                            val confirmedEvent = OrderConfirmedEvent(
                                "evt-${UUID.randomUUID()}", saved.orderId, saved.userEmail,
                                saved.trainId, saved.seatClass, saved.quantity,
                                saved.amount, payRes.transactionId!!
                            )
                            kafkaTemplate.send("eurotransit.order-confirmed", saved.orderId, confirmedEvent)
                        }
                    } else {
                        // payment failed: fail order and release reservation
                        val updated = order.copy(status = "FAILED")
                        orderRepo.save(updated).doOnSuccess { saved ->
                            val failedEvent = OrderFailedEvent(
                                "evt-${UUID.randomUUID()}", saved.orderId, event.reservationId,
                                payRes.reason ?: "payment_declined", saved.userEmail
                            )
                            kafkaTemplate.send("eurotransit.order-failed", saved.orderId, failedEvent)
                        }
                    }
                }
            }.delayUntil { eventRepo.save(ProcessedEvent(event.eventId)) }
        }.block()
    }

    // handle failed inventory reservation
    @KafkaListener(topics = ["eurotransit.inventory-reservation-failed"], groupId = "orders-group")
    fun handleReservationFailed(event: InventoryReservationFailedEvent) {
        eventRepo.existsById(event.eventId).flatMap { exists ->
            if (exists) Mono.empty()
            else orderRepo.findById(event.orderId).flatMap { order ->
                val updated = order.copy(status = "FAILED")
                orderRepo.save(updated).doOnSuccess { saved ->
                    val failedEvent = OrderFailedEvent(
                        "evt-${UUID.randomUUID()}", saved.orderId, null,
                        event.reason, saved.userEmail
                    )
                    kafkaTemplate.send("eurotransit.order-failed", saved.orderId, failedEvent)
                }
            }.delayUntil { eventRepo.save(ProcessedEvent(event.eventId)) }
        }.block()
    }
}