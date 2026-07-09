package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.domain.OutboxEntry
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.OutboxRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
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
        val outboxCaptor = ArgumentCaptor.forClass(OutboxEntry::class.java)
        verify(outboxRepo).save(outboxCaptor.capture())
        val payload = objectMapper.readTree(outboxCaptor.value.payload)
        assert(payload["event_id"].asText().startsWith("evt-"))
        assert(payload["event_timestamp"].asText().isNotBlank())
        assert(payload["reservation_id"].asText() == "res-1")
        verify(processedEventRepo).save(any())
    }
}
