package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.InventoryClient
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
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
import java.util.UUID

@Component
class Stage1Consumer(
    private val inventoryClient: InventoryClient,
    private val orderRepo: OrderRepository,
    private val outboxRepo: OutboxRepository,
    private val processedEventRepo: ProcessedEventRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.order-placed}"])
    @Transactional
    suspend fun consumeOrderPlaced(message: String) {
        val event = objectMapper.readTree(message)
        val eventId = event["event_id"].asText()

        // deduplication check
        if (processedEventRepo.existsById(eventId)) return

        logger.info("Processing order-placed event: $eventId")

        try {
            val reserveRequest = InventoryReserveRequest(
                idempotency_key = event["order_id"].asText(),
                train_id = event["train_id"].asText(),
                seat_class = event["seat_class"].asText(),
                quantity = event["quantity"].asInt()
            )

            // call inventory sync
            val response = inventoryClient.reserveSeats(reserveRequest)
            val order = orderRepo.findById(reserveRequest.idempotency_key)
                ?: throw IllegalStateException("order ${reserveRequest.idempotency_key} not found")
            val nextEventId = "evt-${UUID.randomUUID()}"

            // determine outcome
            val (nextTopic, nextPayload) = if (response.status == "RESERVED") {
                "eurotransit.inventory-reserved" to mapOf(
                    "event_id" to nextEventId,
                    "event_timestamp" to Instant.now().toString(),
                    "order_id" to order.orderId,
                    "reservation_id" to response.reservation_id,
                    "user_id" to order.userId,
                    "amount" to order.amount,
                    "currency" to order.currency
                )
            } else {
                "eurotransit.order-failed" to mapOf(
                    "event_id" to nextEventId,
                    "event_timestamp" to Instant.now().toString(),
                    "order_id" to order.orderId,
                    "reason" to "INSUFFICIENT_SEATS",
                    "user_email" to order.userEmail
                )
            }

            // save to outbox
            outboxRepo.save(OutboxEntry(
                eventId = nextEventId,
                topic = nextTopic,
                payload = objectMapper.writeValueAsString(nextPayload)
            ))

            // mark as processed
            processedEventRepo.save(ProcessedEvent(eventId = eventId))

            logger.info("Stage 1 complete: transitioned to $nextTopic")

        } catch (e: Exception) {
            logger.error("Stage 1 failed for event $eventId: ${e.message}")
            throw e
        }
    }
}
