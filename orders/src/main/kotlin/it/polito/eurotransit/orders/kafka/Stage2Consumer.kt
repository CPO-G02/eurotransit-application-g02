package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import it.polito.eurotransit.orders.entities.OutboxEntry
import it.polito.eurotransit.orders.entities.ProcessedEvent
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Component
class Stage2Consumer(
    private val paymentClient: PaymentClient,
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
            // Ara utilitzem BigDecimal per a l'amount
            val authorizeRequest = PaymentAuthorizeRequest(
                idempotency_key = orderId,
                user_id = event["user_id"].asText(), 
                amount = BigDecimal(event["amount"].asDouble().toString()), 
                currency = event["currency"].asText()
            )

            // call payments sync
            val response = paymentClient.authorizePayment(authorizeRequest)

            // determine outcome
            val (nextTopic, nextPayload) = if (response.status == "AUTHORIZED") {
                "payment-authorized" to mapOf("order_id" to orderId, "status" to "SUCCESS")
            } else {
                "payment-failed" to mapOf("order_id" to orderId, "reason" to "PAYMENT_REJECTED")
            }

            // save to outbox
            outboxRepo.save(OutboxEntry(
                eventId = "evt-$orderId-stage2",
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