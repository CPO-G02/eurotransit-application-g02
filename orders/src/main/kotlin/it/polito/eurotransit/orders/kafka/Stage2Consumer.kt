package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import it.polito.eurotransit.orders.domain.OutboxEntry
import it.polito.eurotransit.orders.domain.ProcessedEvent
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.OutboxRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Component
class Stage2Consumer(
    private val paymentClient: PaymentClient,
    private val orderRepo: OrderRepository,
    private val outboxRepo: OutboxRepository,
    private val processedEventRepo: ProcessedEventRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.inventory-reserved}"])
    @Transactional
    suspend fun consumeInventoryReserved(message: String) {
        val event = objectMapper.readTree(message)
        val eventId = event["event_id"].asText()
        val orderId = event["order_id"].asText()

        // deduplication check
        if (processedEventRepo.existsById(eventId)) return

        logger.info("Processing inventory-reserved event: $eventId")

        try {
            val order = orderRepo.findById(orderId)
                ?: throw IllegalStateException("order $orderId not found")
            val reservedOrder = order.copy(status = "RESERVED")
            orderRepo.save(reservedOrder)

            val authorizeRequest = PaymentAuthorizeRequest(
                idempotency_key = orderId,
                user_id = event["user_id"].asText(), 
                amount = BigDecimal(event["amount"].asDouble().toString()), 
                currency = event["currency"].asText()
            )

            // call payments sync
            val response = paymentClient.authorizePayment(authorizeRequest)
            val nextEventId = "evt-${UUID.randomUUID()}"

            // determine outcome
            val (nextTopic, nextPayload) = if (response.status == "AUTHORIZED") {
                val transactionId = response.transaction_id
                    ?: throw IllegalStateException("authorized payment response missing transaction_id for order $orderId")

                "eurotransit.payment-authorized" to mapOf(
                    "event_id" to nextEventId,
                    "event_timestamp" to Instant.now().toString(),
                    "order_id" to orderId,
                    "transaction_id" to transactionId,
                    "amount" to authorizeRequest.amount,
                    "currency" to authorizeRequest.currency
                )
            } else {
                "eurotransit.payment-failed" to mapOf(
                    "event_id" to nextEventId,
                    "event_timestamp" to Instant.now().toString(),
                    "order_id" to orderId,
                    "reservation_id" to event["reservation_id"]?.asText(),
                    "reason" to (response.reason ?: "PAYMENT_REJECTED")
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

            logger.info("Stage 2 complete: transitioned to $nextTopic")

        } catch (e: Exception) {
            logger.error("Stage 2 failed for event $eventId: ${e.message}")
            throw e
        }
    }
}
