package it.polito.eurotransit.orders

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.entities.ProcessedRequest
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedRequestRepository
import it.polito.eurotransit.orders.service.OrderServiceImpl 
import it.polito.eurotransit.orders.metrics.OrdersPromotionMetrics
import it.polito.eurotransit.orders.metrics.OrdersPromotionCommitMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
        val commitMetrics = mock<OrdersPromotionCommitMetrics>()

        val orderService = OrderServiceImpl(
            orderRepo,
            requestRepo,
            outboxRepo,
            objectMapper,
            OrdersPromotionMetrics(SimpleMeterRegistry()),
            commitMetrics
        )

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

        whenever(requestRepo.insertIfAbsent(eq("idem-999"), any())).thenReturn(1)
        whenever(orderRepo.insertNew(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )).thenReturn(1)
        whenever(outboxRepo.insert(any(), any(), any())).thenReturn(1)

        val resultOrder = orderService.createOrder(request)

        assertEquals("PENDING", resultOrder.status)
        verify(commitMetrics).recordNewOrderAfterCommit()
        verify(commitMetrics, never()).recordReplayAfterCommit()
        
        verify(outboxRepo, times(1)).insert(
            argThat { eventId -> eventId.startsWith("evt-") },
            eq("eurotransit.order-placed"),
            argThat { payload ->
            val event = objectMapper.readTree(payload)
            assertEquals(resultOrder.orderId, event["order_id"].asText())
            assertEquals("tr-456", event["train_id"].asText())
            assertEquals("STANDARD", event["seat_class"].asText())
            assertEquals(1, event["quantity"].asInt())
            assertEquals(true, event.has("event_id"))
            assertEquals(true, event.has("event_timestamp"))
            assertEquals(false, event.has("eventId"))
            assertEquals(false, event.has("orderId"))
            assertEquals(false, event.has("trainId"))
            event["event_id"].asText().startsWith("evt-")
        })
    }

    @Test
    fun `idempotent replay is recorded separately and does not create another outbox entry`() = runTest {
        val orderRepo = mock<OrderRepository>()
        val requestRepo = mock<ProcessedRequestRepository>()
        val outboxRepo = mock<OutboxRepository>()
        val commitMetrics = mock<OrdersPromotionCommitMetrics>()
        val service = OrderServiceImpl(
            orderRepo,
            requestRepo,
            outboxRepo,
            ObjectMapper(),
            OrdersPromotionMetrics(SimpleMeterRegistry()),
            commitMetrics
        )
        val request = OrderRequest(
            idempotencyKey = "idem-replay",
            userId = "usr-1",
            userEmail = "client@example.com",
            trainId = "tr-456",
            seatClass = "STANDARD",
            quantity = 1,
            amount = BigDecimal("25.00"),
            currency = "EUR"
        )
        val existing = Order(
            orderId = "ord-existing",
            userId = request.userId,
            userEmail = request.userEmail,
            trainId = request.trainId,
            seatClass = request.seatClass,
            quantity = request.quantity,
            amount = request.amount,
            currency = request.currency,
            status = "PENDING"
        )

        whenever(requestRepo.insertIfAbsent(eq("idem-replay"), any())).thenReturn(0)
        whenever(requestRepo.findById("idem-replay")).thenReturn(ProcessedRequest("idem-replay", existing.orderId))
        whenever(orderRepo.findById(existing.orderId)).thenReturn(existing)

        assertEquals(existing, service.createOrder(request))
        verify(commitMetrics).recordReplayAfterCommit()
        verify(commitMetrics, never()).recordNewOrderAfterCommit()
        verifyNoInteractions(outboxRepo)
    }
}
