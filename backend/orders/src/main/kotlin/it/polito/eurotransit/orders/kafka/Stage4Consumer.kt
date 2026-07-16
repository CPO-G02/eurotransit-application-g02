package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class Stage4Consumer(
    private val orderRepo: OrderRepository,
    private val outboxRepo: OutboxRepository,
    private val processedEventRepo: ProcessedEventRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${app.kafka.topics.order-failed}")
    private val orderFailedTopic: String = "eurotransit.order-failed",
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.payment-failed}"])
    @Transactional
    suspend fun consumePaymentFailed(message: String) {
        val event = objectMapper.readTree(message)
        val eventId = event["event_id"].asText()
        val orderId = event["order_id"].asText()

        if (processedEventRepo.insertIfAbsent(eventId) == 0) return

        logger.info("Processing payment-failed event: $eventId for order $orderId")

        try {
            // update order status in database
            val order = orderRepo.findById(orderId) 
                ?: throw IllegalStateException("order $orderId not found")
            
            val failedOrder = order.copy(status = "FAILED")
            orderRepo.save(failedOrder)

            // save failure notification to outbox
            val nextPayload = mutableMapOf(
                "event_id" to "evt-$orderId-stage4",
                "event_timestamp" to Instant.now().toString(),
                "order_id" to orderId,
                "reason" to event.path("reason").asText("PAYMENT_REJECTED"),
                "user_email" to order.userEmail,
            )
            val reservationId = event.path("reservation_id").asText(null)
            if (reservationId != null) {
                nextPayload["reservation_id"] = reservationId
            }
            outboxRepo.insert(
                eventId = nextPayload.getValue("event_id").toString(),
                topic = orderFailedTopic,
                payload = objectMapper.writeValueAsString(nextPayload),
            )

            logger.info("Stage 4 complete: order $orderId marked as failed")

        } catch (e: Exception) {
            logger.error("Stage 4 failed for event $eventId: ${e.message}")
            throw e
        }
    }
}
