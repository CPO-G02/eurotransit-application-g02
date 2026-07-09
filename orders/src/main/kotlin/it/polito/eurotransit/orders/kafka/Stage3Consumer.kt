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
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

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
                transactionId = event["transaction_id"]?.asText()
                    ?: throw IllegalArgumentException("missing transaction_id in payment-authorized event $eventId"),
                confirmedAt = LocalDateTime.now()
            )
            orderRepo.save(confirmedOrder)

            // save confirmation to outbox
            val nextEventId = "evt-${UUID.randomUUID()}"
            val nextPayload = mapOf(
                "event_id" to nextEventId,
                "event_timestamp" to Instant.now().toString(),
                "order_id" to confirmedOrder.orderId,
                "user_email" to confirmedOrder.userEmail,
                "train_id" to confirmedOrder.trainId,
                "seat_class" to confirmedOrder.seatClass,
                "quantity" to confirmedOrder.quantity,
                "amount" to confirmedOrder.amount,
                "transaction_id" to confirmedOrder.transactionId
            )
            outboxRepo.save(OutboxEntry(
                eventId = nextEventId,
                topic = "eurotransit.order-confirmed",
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
