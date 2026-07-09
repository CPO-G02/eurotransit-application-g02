package it.polito.eurotransit.orders.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.repository.OutboxRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class OutboxRelay(
    private val outboxRepo: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000) // poll every second
    fun pollOutbox() = runBlocking {
        processPendingMessages()
    }

    @Transactional
    suspend fun processPendingMessages() {
        // fetch and lock pending messages
        val messages = outboxRepo.findPendingMessages(limit = 10)
        if (messages.isEmpty()) return

        for (entry in messages) {
            try {
                // parse raw json payload to generic map for kafka json serializer
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