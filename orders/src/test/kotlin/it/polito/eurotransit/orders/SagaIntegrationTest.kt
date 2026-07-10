package it.polito.eurotransit.orders

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.OutboxRepository
import it.polito.eurotransit.orders.repository.ProcessedRequestRepository
import it.polito.eurotransit.orders.service.OrderService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.math.BigDecimal

class SagaIntegrationTest {

    @Test
    fun `create order successfully saves to database and publishes to outbox`() = runTest {
        val orderRepo = mock<OrderRepository>()
        val requestRepo = mock<ProcessedRequestRepository>()
        val outboxRepo = mock<OutboxRepository>()
        val objectMapper = ObjectMapper()

        val orderService = OrderService(orderRepo, requestRepo, outboxRepo, objectMapper)

        val request = OrderRequest(
            idempotencyKey = "idem-999",
            userId = "usr-1",
            userEmail = "client@example.com",
            trainId = "tr-456",
            seatClass = "STANDARD",
            quantity = 1,
            amount = BigDecimal("25.00"),
            currency = "EUR"
        )

        whenever(requestRepo.findById("idem-999")).thenReturn(null)
        whenever(orderRepo.save(any())).thenAnswer { it.arguments[0] }

        val resultOrder = orderService.createOrder(request)

        assertEquals("PENDING", resultOrder.status)
        
        verify(outboxRepo, times(1)).save(argThat { 
            topic == "eurotransit.order-placed" && payload.contains(resultOrder.orderId) 
        })
    }
}