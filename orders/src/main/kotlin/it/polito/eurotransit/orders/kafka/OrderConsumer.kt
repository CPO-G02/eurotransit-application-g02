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
            else orderRepo.findById(event.orderId).flatMap { nullableOrder ->
                val order = nullableOrder!! // obliguem a kotlin a entendre que no és nul
                
                val payReq = PaymentRequest(order.orderId, order.userId, order.amount, order.currency)
                
                paymentClient.authorizePayment(payReq).flatMap { payRes ->
                    if (payRes.status == "AUTHORIZED") {
                        val updated = order.copy(
                            status = "CONFIRMED", 
                            transactionId = payRes.transactionId, 
                            confirmedAt = LocalDateTime.now()
                        )
                        orderRepo.save(updated).map { saved ->
                            val safeSaved = saved!!
                            val confirmedEvent = OrderConfirmedEvent(
                                "evt-${UUID.randomUUID()}", safeSaved.orderId, safeSaved.userEmail,
                                safeSaved.trainId, safeSaved.seatClass, safeSaved.quantity,
                                safeSaved.amount, payRes.transactionId!!
                            )
                            kafkaTemplate.send("eurotransit.order-confirmed", safeSaved.orderId, confirmedEvent)
                            safeSaved
                        }
                    } else {
                        val updated = order.copy(status = "FAILED")
                        orderRepo.save(updated).map { saved ->
                            val safeSaved = saved!!
                            val failedEvent = OrderFailedEvent(
                                "evt-${UUID.randomUUID()}", safeSaved.orderId, event.reservationId,
                                payRes.reason ?: "payment_declined", safeSaved.userEmail
                            )
                            kafkaTemplate.send("eurotransit.order-failed", safeSaved.orderId, failedEvent)
                            safeSaved
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
            else orderRepo.findById(event.orderId).flatMap { nullableOrder ->
                val order = nullableOrder!!
                val updated = order.copy(status = "FAILED")
                orderRepo.save(updated).map { saved ->
                    val safeSaved = saved!!
                    val failedEvent = OrderFailedEvent(
                        "evt-${UUID.randomUUID()}", safeSaved.orderId, null,
                        event.reason, safeSaved.userEmail
                    )
                    kafkaTemplate.send("eurotransit.order-failed", safeSaved.orderId, failedEvent)
                    safeSaved
                }
            }.delayUntil { eventRepo.save(ProcessedEvent(event.eventId)) }
        }.block()
    }
}