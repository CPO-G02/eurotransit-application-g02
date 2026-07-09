package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.domain.OutboxEntry
import it.polito.eurotransit.orders.dto.PaymentAuthorizeResponse
import it.polito.eurotransit.orders.repository.OutboxRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class Stage2ConsumerTest {

    private val paymentClient = mock(PaymentClient::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage2Consumer(paymentClient, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should process inventory-reserved and save authorized to outbox`() = runBlocking {
        val message = """{
            "event_id": "evt-2", 
            "order_id": "ord-1", 
            "reservation_id": "res-1",
            "user_id": "user-1", 
            "amount": 100.0, 
            "currency": "EUR"
        }"""
        
        whenever(processedEventRepo.existsById("evt-2")).thenReturn(false)
        
        whenever(paymentClient.authorizePayment(any())).thenReturn(PaymentAuthorizeResponse(
            transaction_id = "tx-1", 
            status = "AUTHORIZED", 
            reason = null
        ))
        
        consumer.consumeInventoryReserved(message)
        
        val outboxCaptor = ArgumentCaptor.forClass(OutboxEntry::class.java)
        verify(outboxRepo).save(outboxCaptor.capture())
        val payload = objectMapper.readTree(outboxCaptor.value.payload)
        assert(payload["event_id"].asText().startsWith("evt-"))
        assert(payload["event_timestamp"].asText().isNotBlank())
        assert(payload["transaction_id"].asText() == "tx-1")
        verify(processedEventRepo).save(any())
    }
}
