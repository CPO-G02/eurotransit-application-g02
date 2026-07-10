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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class Stage4ConsumerTest {

    private val orderRepo = mock(OrderRepository::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage4Consumer(orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should mark order as FAILED when payment-failed event received`() = runBlocking {
        val orderId = "ord-1"
        val message = """{"event_id": "evt-4", "order_id": "$orderId", "reservation_id": "res-1", "reason": "payment_declined"}"""
        
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
        whenever(processedEventRepo.existsById("evt-4")).thenReturn(false)
        
        consumer.consumePaymentFailed(message)
        
        verify(orderRepo).save(argThat { order -> order.status == "FAILED" })
        val outboxCaptor = argumentCaptor<OutboxEntry>()
        verify(outboxRepo).save(outboxCaptor.capture())
        val payload = objectMapper.readTree(outboxCaptor.firstValue.payload)
        assertTrue(payload["event_id"].asText().startsWith("evt-"))
        assertTrue(payload["event_timestamp"].asText().isNotBlank())
        assertEquals("res-1", payload["reservation_id"].asText())
        assertEquals("payment_declined", payload["reason"].asText())
        verify(processedEventRepo).save(any())
    }

    @Test
    fun `should use canonical payment rejected reason when payment-failed event has no reason`() = runBlocking {
        val orderId = "ord-1"
        val message = """{"event_id": "evt-4", "order_id": "$orderId", "reservation_id": "res-1"}"""

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
        whenever(processedEventRepo.existsById("evt-4")).thenReturn(false)

        consumer.consumePaymentFailed(message)

        val outboxCaptor = argumentCaptor<OutboxEntry>()
        verify(outboxRepo).save(outboxCaptor.capture())
        val payload = objectMapper.readTree(outboxCaptor.firstValue.payload)
        assertEquals("PAYMENT_REJECTED", payload["reason"].asText())
    }
}
