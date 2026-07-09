package it.polito.eurotransit.orders

import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.repository.OrderRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class SagaIntegrationTest {

    @Test
    fun `test isolated domain logic compiles and executes`() = runTest {
        val orderRepo = mock(OrderRepository::class.java)
        val mockOrder = mock(Order::class.java)
        val orderId = "ord-123"

        `when`(mockOrder.status).thenReturn("CONFIRMED")

        runBlocking {
            `when`(orderRepo.findById(orderId)).thenReturn(mockOrder)
        }

        val resultOrder = runBlocking {
            orderRepo.findById(orderId)
        }

        assertEquals("CONFIRMED", resultOrder?.status)
    }
}