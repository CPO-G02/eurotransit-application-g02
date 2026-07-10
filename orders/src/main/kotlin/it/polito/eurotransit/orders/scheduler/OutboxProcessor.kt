package it.polito.eurotransit.orders.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.repositories.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class OutboxProcessor(
    private val outboxRepo: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun processPendingMessages() {
        // fetch and lock pending messages
        val messages = outboxRepo.findPendingMessages(limit = 10)
        if (messages.isEmpty()) return

        for (entry in messages) {
            try {
                val payloadMap = objectMapper.readValue(entry.payload, Map::class.java)

                // send to kafka using event id as key
                kafkaTemplate.send(entry.topic, entry.eventId, payloadMap).get()

                // mark as sent in database
                val updated = entry.copy(sentAt = LocalDateTime.now())
                outboxRepo.save(updated)

                logger.info("Published event ${entry.eventId} to topic ${entry.topic}")
            } catch (e: Exception) {
                logger.error("Failed to publish outbox event ${entry.eventId}: ${e.message}")
                throw e
            }
        }
    }
}