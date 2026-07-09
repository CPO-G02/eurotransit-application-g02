package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.OutboxRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class Stage3ConsumerTest {

    private val orderRepo = mock(OrderRepository::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage3Consumer(orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should confirm order when payment-authorized event received`() = runBlocking {
        val orderId = "ord-1"
        val message = """{"event_id": "evt-3", "order_id": "$orderId"}"""
        
        val existingOrder = mock(Order::class.java)
        whenever(existingOrder.orderId).thenReturn(orderId)
        whenever(existingOrder.copy(status = any(), confirmedAt = any())).thenReturn(existingOrder)
        
        whenever(orderRepo.findById(orderId)).thenReturn(existingOrder)
        whenever(processedEventRepo.existsById("evt-3")).thenReturn(false)
        
        consumer.consumePaymentAuthorized(message)
        
        verify(orderRepo).save(any())
        verify(outboxRepo).save(any())
        verify(processedEventRepo).save(any())
    }
}