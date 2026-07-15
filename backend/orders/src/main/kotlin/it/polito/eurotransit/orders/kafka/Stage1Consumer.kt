package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.InventoryClient
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
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
class Stage1Consumer(
    private val inventoryClient: InventoryClient,
    private val orderRepo: OrderRepository,
    private val outboxRepo: OutboxRepository,
    private val processedEventRepo: ProcessedEventRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${app.kafka.topics.inventory-reserved}")
    private val inventoryReservedTopic: String = "eurotransit.inventory-reserved",
    @Value("\${app.kafka.topics.order-failed}")
    private val orderFailedTopic: String = "eurotransit.order-failed",
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.order-placed}"])
    @Transactional
    suspend fun consumeOrderPlaced(message: String) {
        val event = objectMapper.readTree(message)
        val eventId = event["event_id"].asText()
        val orderId = event["order_id"].asText()

        if (processedEventRepo.insertIfAbsent(eventId) == 0) return

        logger.info("Processing order-placed event: $eventId")

        try {
            val order = orderRepo.findById(orderId)
                ?: throw IllegalStateException("order $orderId not found")
            val reserveRequest = InventoryReserveRequest(
                idempotency_key = orderId,
                train_id = event["train_id"].asText(),
                seat_class = event["seat_class"].asText(),
                quantity = event["quantity"].asInt()
            )

            // call inventory sync
            val response = inventoryClient.reserveSeats(reserveRequest)

            // determine outcome
            val (nextTopic, nextPayload) = if (response.status == "RESERVED") {
                val reservationId = response.reservation_id
                    ?: throw IllegalStateException("inventory reserved order $orderId without reservation_id")
                inventoryReservedTopic to mapOf(
                    "event_id" to "evt-$orderId-stage1",
                    "event_timestamp" to Instant.now().toString(),
                    "order_id" to orderId,
                    "reservation_id" to reservationId,
                    "user_id" to order.userId,
                    "amount" to order.amount,
                    "currency" to order.currency,
                )
            } else {
                orderRepo.save(order.copy(status = "FAILED"))
                orderFailedTopic to mapOf(
                    "event_id" to "evt-$orderId-stage1",
                    "event_timestamp" to Instant.now().toString(),
                    "order_id" to orderId,
                    "reason" to "INSUFFICIENT_SEATS",
                    "user_email" to order.userEmail,
                )
            }

            // save to outbox
            outboxRepo.insert(
                eventId = nextPayload.getValue("event_id").toString(),
                topic = nextTopic,
                payload = objectMapper.writeValueAsString(nextPayload),
            )

            logger.info("Stage 1 complete: transitioned to $nextTopic")

        } catch (e: Exception) {
            logger.error("Stage 1 failed for event $eventId: ${e.message}")
            throw e
        }
    }
}
