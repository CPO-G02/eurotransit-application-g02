package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
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
class Stage2Consumer(
    private val paymentClient: PaymentClient,
    private val orderRepo: OrderRepository,
    private val outboxRepo: OutboxRepository,
    private val processedEventRepo: ProcessedEventRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${app.kafka.topics.payment-authorized}")
    private val paymentAuthorizedTopic: String = "eurotransit.payment-authorized",
    @Value("\${app.kafka.topics.payment-failed}")
    private val paymentFailedTopic: String = "eurotransit.payment-failed",
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.inventory-reserved}"])
    @Transactional
    suspend fun consumeInventoryReserved(message: String) {
        val event = objectMapper.readTree(message)
        val eventId = event["event_id"].asText()
        val orderId = event["order_id"].asText()
        val reservationId = event["reservation_id"].asText()

        if (processedEventRepo.insertIfAbsent(eventId) == 0) return

        logger.info("Processing inventory-reserved event: $eventId")

        try {
            val order = orderRepo.findById(orderId)
                ?: throw IllegalStateException("order $orderId not found")
            orderRepo.save(order.copy(status = "RESERVED"))

            val authorizeRequest = PaymentAuthorizeRequest(
                idempotency_key = orderId,
                user_id = event["user_id"].asText(), 
                amount = event["amount"].decimalValue(),
                currency = event["currency"].asText()
            )

            // call payments sync
            val response = paymentClient.authorizePayment(authorizeRequest)

            // determine outcome
            val (nextTopic, nextPayload) = if (response.status == "AUTHORIZED") {
                val transactionId = response.transaction_id
                    ?: throw IllegalStateException("payments authorized order $orderId without transaction_id")
                paymentAuthorizedTopic to mapOf(
                    "event_id" to "evt-$orderId-stage2",
                    "event_timestamp" to Instant.now().toString(),
                    "order_id" to orderId,
                    "transaction_id" to transactionId,
                    "amount" to authorizeRequest.amount,
                    "currency" to authorizeRequest.currency,
                )
            } else {
                paymentFailedTopic to mapOf(
                    "event_id" to "evt-$orderId-stage2",
                    "event_timestamp" to Instant.now().toString(),
                    "order_id" to orderId,
                    "reservation_id" to reservationId,
                    "reason" to (response.reason ?: "PAYMENT_REJECTED"),
                )
            }

            // save to outbox
            outboxRepo.insert(
                eventId = nextPayload.getValue("event_id").toString(),
                topic = nextTopic,
                payload = objectMapper.writeValueAsString(nextPayload),
            )

            logger.info("Stage 2 complete: transitioned to $nextTopic")

        } catch (e: Exception) {
            logger.error("Stage 2 failed for event $eventId: ${e.message}")
            throw e
        }
    }
}
