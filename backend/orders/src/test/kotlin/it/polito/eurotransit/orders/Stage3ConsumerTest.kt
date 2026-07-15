package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.metrics.OrderSloMetrics
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
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
    // Real instance (backed by a real, throwaway registry) rather than a mock -
    // it's a plain metrics recorder with no external dependencies, cheaper to
    // use for real than to mock.
    private val orderSloMetrics = OrderSloMetrics(SimpleMeterRegistry())

    private val consumer = Stage3Consumer(orderRepo, outboxRepo, processedEventRepo, objectMapper, orderSloMetrics)

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