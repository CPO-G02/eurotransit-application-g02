package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.metrics.OrderSloMetrics
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@Component
class Stage3Consumer(
    private val orderRepo: OrderRepository,
    private val outboxRepo: OutboxRepository,
    private val processedEventRepo: ProcessedEventRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${app.kafka.topics.order-confirmed}")
    private val orderConfirmedTopic: String = "eurotransit.order-confirmed",
    private val orderSloMetrics: OrderSloMetrics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.payment-authorized}"])
    @Transactional
    suspend fun consumePaymentAuthorized(message: String) {
        val event = objectMapper.readTree(message)
        val eventId = event["event_id"].asText()
        val orderId = event["order_id"].asText()
        val transactionId = event["transaction_id"].asText()

        if (processedEventRepo.insertIfAbsent(eventId) == 0) return

        logger.info("Processing payment-authorized event: $eventId for order $orderId")

        try {
            // update order status in database
            val order = orderRepo.findById(orderId) 
                ?: throw IllegalStateException("order $orderId not found")

            val confirmedAt = LocalDateTime.now()
            val confirmedOrder = order.copy(
                status = "CONFIRMED", 
                transactionId = transactionId,
                confirmedAt = confirmedAt,
            )
            orderRepo.save(confirmedOrder)

            // contract §4.1 SLI: time from order creation to CONFIRMED
            order.createdAt?.let { createdAt ->
                orderSloMetrics.recordConfirmationLatency(Duration.between(createdAt, confirmedAt))
            }

            // save confirmation to outbox
            val nextPayload = mapOf(
                "event_id" to "evt-$orderId-stage3",
                "event_timestamp" to Instant.now().toString(),
                "order_id" to orderId,
                "user_email" to order.userEmail,
                "train_id" to order.trainId,
                "seat_class" to order.seatClass,
                "quantity" to order.quantity,
                "amount" to order.amount,
                "transaction_id" to transactionId,
            )
            outboxRepo.insert(
                eventId = nextPayload.getValue("event_id").toString(),
                topic = orderConfirmedTopic,
                payload = objectMapper.writeValueAsString(nextPayload),
            )

            logger.info("Stage 3 complete: order $orderId confirmed")

        } catch (e: Exception) {
            logger.error("Stage 3 failed for event $eventId: ${e.message}")
            throw e
        }
    }
}
