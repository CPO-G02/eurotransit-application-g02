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

class Stage4ConsumerTest {

    private val orderRepo = mock(OrderRepository::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage4Consumer(orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should mark order as FAILED when payment-failed event received`() = runBlocking {
        val orderId = "ord-1"
        val message = """{"event_id": "evt-4", "order_id": "$orderId"}"""
        
        val existingOrder = mock(Order::class.java)
        whenever(existingOrder.orderId).thenReturn(orderId)
        whenever(existingOrder.copy(status = "FAILED")).thenReturn(existingOrder)
        
        whenever(orderRepo.findById(orderId)).thenReturn(existingOrder)
        whenever(processedEventRepo.existsById("evt-4")).thenReturn(false)
        
        consumer.consumePaymentFailed(message)
        
        verify(orderRepo).save(argThat { order -> order.status == "FAILED" })
        verify(outboxRepo).save(any())
        verify(processedEventRepo).save(any())
    }
}