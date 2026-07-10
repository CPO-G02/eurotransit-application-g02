package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.entities.OutboxEntry
import it.polito.eurotransit.orders.entities.ProcessedEvent
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class Stage3Consumer(
    private val orderRepo: OrderRepository,
    private val outboxRepo: OutboxRepository,
    private val processedEventRepo: ProcessedEventRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.payment-authorized}"])
    @Transactional
    suspend fun consumePaymentAuthorized(message: String) {
        val event = objectMapper.readTree(message)
        val eventId = event["event_id"].asText()
        val orderId = event["order_id"].asText()

        // deduplication check
        if (processedEventRepo.existsById(eventId)) return

        logger.info("Processing payment-authorized event: $eventId for order $orderId")

        try {
            // update order status in database
            val order = orderRepo.findById(orderId) 
                ?: throw IllegalStateException("order $orderId not found")
            
            val confirmedOrder = order.copy(
                status = "CONFIRMED", 
                confirmedAt = LocalDateTime.now()
            )
            orderRepo.save(confirmedOrder)

            // save confirmation to outbox
            val nextPayload = mapOf("order_id" to orderId, "status" to "CONFIRMED")
            outboxRepo.save(OutboxEntry(
                eventId = "evt-$orderId-stage3",
                topic = "order-confirmed",
                payload = objectMapper.writeValueAsString(nextPayload)
            ))

            // mark as processed
            processedEventRepo.save(ProcessedEvent(eventId = eventId))

            logger.info("Stage 3 complete: order $orderId confirmed")

        } catch (e: Exception) {
            logger.error("Stage 3 failed for event $eventId: ${e.message}")
            throw e
        }
    }
}