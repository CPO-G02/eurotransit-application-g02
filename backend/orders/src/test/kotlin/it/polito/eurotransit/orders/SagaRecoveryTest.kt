package it.polito.eurotransit.orders

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.entities.OutboxEntry
import it.polito.eurotransit.orders.scheduler.OutboxProcessor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

class SagaRecoveryTest {

    private lateinit var outboxRepo: OutboxRepository
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>
    private val objectMapper = ObjectMapper()
    
    private lateinit var outboxProcessor: OutboxProcessor

    @BeforeEach
    fun setup() {
        outboxRepo = mock()
        kafkaTemplate = mock()
        
        outboxProcessor = OutboxProcessor(outboxRepo, kafkaTemplate, objectMapper)
    }

    @Test
    fun `should process and publish pending messages from outbox`() = runTest {
        val eventId = "evt-123"
        val payload = """{"order_id": "ord-999", "status": "PENDING"}"""
        
        val pendingEntry = OutboxEntry(
            id = 1L,
            eventId = eventId,
            topic = "eurotransit.orders",
            payload = payload,
            createdAt = LocalDateTime.now(),
            sentAt = null
        )

        whenever(outboxRepo.findPendingMessages(any())).thenReturn(listOf(pendingEntry))

        val future = mock<CompletableFuture<SendResult<String, Any>>>()
        whenever(kafkaTemplate.send(any<String>(), any(), any())).thenReturn(future)
        whenever(outboxRepo.markSent(any(), any())).thenReturn(1)

        outboxProcessor.processPendingMessages()

        verify(kafkaTemplate, times(1)).send(eq("eurotransit.orders"), eq(eventId), any())
        verify(outboxRepo, times(1)).markSent(eq(1L), any())
    }
}