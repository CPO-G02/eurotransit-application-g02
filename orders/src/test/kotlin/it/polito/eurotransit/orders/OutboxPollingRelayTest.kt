package it.polito.eurotransit.orders.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.domain.OutboxEntry
import it.polito.eurotransit.orders.repository.OutboxRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

class OutboxRelayTest {

    private val outboxRepo = mock(OutboxRepository::class.java)
    // Utilitzem un mock que respecti la genèrica de Kafka
    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate = mock(KafkaTemplate::class.java) as KafkaTemplate<String, Any>
    private val objectMapper = ObjectMapper()
    
    private val relay = OutboxRelay(outboxRepo, kafkaTemplate, objectMapper)

    @Test
    fun `should publish pending messages to kafka and mark as sent`() = runBlocking {
        // 1. Simulem una entrada a l'outbox amb dades correctes
        val entry = OutboxEntry(
            id = 1L, 
            eventId = "evt-1", 
            topic = "test-topic", 
            payload = """{"key":"value"}"""
        )
        whenever(outboxRepo.findPendingMessages(anyInt())).thenReturn(listOf(entry))
        
        // 2. Mock de la Future de Kafka amb el tipus SendResult correcte
        val future = CompletableFuture<SendResult<String, Any>>()
        val sendResult = mock(SendResult::class.java) as SendResult<String, Any>
        future.complete(sendResult)
        
        whenever(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future)
        
        // 3. Executem el relay
        relay.processPendingMessages()
        
        // 4. Verifiquem que l'enviament ha passat i que l'entrada s'ha guardat (sentAt != null)
        verify(kafkaTemplate).send(eq("test-topic"), eq("evt-1"), any())
        verify(outboxRepo).save(argThat { it.sentAt != null })
    }
}