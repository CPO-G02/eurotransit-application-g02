package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.domain.OutboxEntry
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.OutboxRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class Stage3ConsumerTest {

    private val orderRepo = mock(OrderRepository::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage3Consumer(orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should confirm order when payment-authorized event received`() = runBlocking {
        val orderId = "ord-1"
        val message = """{"event_id": "evt-3", "order_id": "$orderId", "transaction_id": "tx-1"}"""
        
        val existingOrder = Order(
            orderId = orderId,
            userId = "user-1",
            userEmail = "user@example.com",
            trainId = "T1",
            seatClass = "first",
            quantity = 1,
            amount = BigDecimal("100.00"),
            currency = "EUR",
            status = "RESERVED"
        )
        
        whenever(orderRepo.findById(orderId)).thenReturn(existingOrder)
        whenever(processedEventRepo.existsById("evt-3")).thenReturn(false)
        
        consumer.consumePaymentAuthorized(message)
        
        verify(orderRepo).save(any())
        val outboxCaptor = ArgumentCaptor.forClass(OutboxEntry::class.java)
        verify(outboxRepo).save(outboxCaptor.capture())
        val payload = objectMapper.readTree(outboxCaptor.value.payload)
        assertTrue(payload["event_id"].asText().startsWith("evt-"))
        assertTrue(payload["event_timestamp"].asText().isNotBlank())
        assertEquals("tx-1", payload["transaction_id"].asText())
        verify(processedEventRepo).save(any())
    }
}
