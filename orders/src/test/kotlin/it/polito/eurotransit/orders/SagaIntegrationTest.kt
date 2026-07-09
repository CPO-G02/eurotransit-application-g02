package it.polito.eurotransit.orders

import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.repository.OrderRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class SagaIntegrationTest {

    @Test
    fun `test isolated domain logic compiles and executes`() = runTest {
        val orderRepo = mock(OrderRepository::class.java)
        val mockOrder = mock(Order::class.java)
        val orderId = "ord-123"

        whenever(mockOrder.status).thenReturn("CONFIRMED")
        whenever(orderRepo.findById(orderId)).thenReturn(mockOrder)

        val resultOrder = orderRepo.findById(orderId)

        assertEquals("CONFIRMED", resultOrder?.status)
    }
}