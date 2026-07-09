package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.domain.OutboxEntry
import it.polito.eurotransit.orders.domain.ProcessedEvent
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.OutboxRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class Stage4Consumer(
    private val orderRepo: OrderRepository,
    private val outboxRepo: OutboxRepository,
    private val processedEventRepo: ProcessedEventRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.payment-failed}"])
    @Transactional
    suspend fun consumePaymentFailed(message: String) {
        val event = objectMapper.readTree(message)
        val eventId = event["event_id"].asText()
        val orderId = event["order_id"].asText()

        // deduplication check
        if (processedEventRepo.existsById(eventId)) return

        logger.info("Processing payment-failed event: $eventId for order $orderId")

        try {
            // update order status in database
            val order = orderRepo.findById(orderId) 
                ?: throw IllegalStateException("order $orderId not found")
            
            val failedOrder = order.copy(status = "FAILED")
            orderRepo.save(failedOrder)

            // save failure notification to outbox
            val nextPayload = mapOf("order_id" to orderId, "status" to "FAILED")
            outboxRepo.save(OutboxEntry(
                eventId = "evt-$orderId-stage4",
                topic = "order-failed",
                payload = objectMapper.writeValueAsString(nextPayload)
            ))

            // mark as processed
            processedEventRepo.save(ProcessedEvent(eventId = eventId))

            logger.info("Stage 4 complete: order $orderId marked as failed")

        } catch (e: Exception) {
            logger.error("Stage 4 failed for event $eventId: ${e.message}")
            throw e
        }
    }
}