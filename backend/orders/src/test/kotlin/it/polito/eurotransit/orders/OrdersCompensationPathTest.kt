package it.polito.eurotransit.orders

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.dto.PaymentAuthorizeResponse
import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.kafka.Stage2Consumer
import it.polito.eurotransit.orders.kafka.Stage4Consumer
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OrdersCompensationPathTest {

    private val paymentClient = mock<PaymentClient>()
    private val orderRepo = mock<OrderRepository>()
    private val outboxRepo = mock<OutboxRepository>()
    private val processedEventRepo = mock<ProcessedEventRepository>()
    private val objectMapper = ObjectMapper()

    private val stage2 = Stage2Consumer(paymentClient, orderRepo, outboxRepo, processedEventRepo, objectMapper)
    private val stage4 = Stage4Consumer(orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `payment decline propagates reservation id and reason to final order-failed event`() = runBlocking {
        whenever(orderRepo.findById("ord-1")).thenReturn(order())
        whenever(processedEventRepo.insertIfAbsent("evt-inventory-reserved")).thenReturn(1)
        whenever(processedEventRepo.insertIfAbsent("evt-ord-1-stage2")).thenReturn(1)
        whenever(paymentClient.authorizePayment(any())).thenReturn(
            PaymentAuthorizeResponse(
                transaction_id = null,
                status = "DECLINED",
                reason = "insufficient_funds",
            ),
        )

        stage2.consumeInventoryReserved(
            """
            {
              "event_id": "evt-inventory-reserved",
              "event_timestamp": "2026-07-15T10:00:00.100Z",
              "order_id": "ord-1",
              "reservation_id": "res-777",
              "user_id": "user-1",
              "amount": 45.50,
              "currency": "EUR"
            }
            """.trimIndent(),
        )

        val stage2Payload = argumentCaptor<String>()
        verify(outboxRepo).insert(
            eq("evt-ord-1-stage2"),
            eq("eurotransit.payment-failed"),
            stage2Payload.capture(),
        )
        val paymentFailed = objectMapper.readTree(stage2Payload.firstValue)
        assertEquals("res-777", paymentFailed["reservation_id"].asText())
        assertEquals("insufficient_funds", paymentFailed["reason"].asText())

        clearInvocations(outboxRepo)

        stage4.consumePaymentFailed(stage2Payload.firstValue)

        val stage4Payload = argumentCaptor<String>()
        verify(outboxRepo, times(1)).insert(
            eq("evt-ord-1-stage4"),
            eq("eurotransit.order-failed"),
            stage4Payload.capture(),
        )

        val orderFailed = objectMapper.readTree(stage4Payload.firstValue)
        assertEquals("evt-ord-1-stage4", orderFailed["event_id"].asText())
        assertEquals("ord-1", orderFailed["order_id"].asText())
        assertEquals("res-777", orderFailed["reservation_id"].asText())
        assertEquals("insufficient_funds", orderFailed["reason"].asText())
        assertEquals("client@example.com", orderFailed["user_email"].asText())
        assertFalse(orderFailed.has("reservationId"))
        assertFalse(orderFailed.has("userEmail"))
    }

    private fun order() = Order(
        orderId = "ord-1",
        userId = "user-1",
        userEmail = "client@example.com",
        trainId = "TR-101",
        seatClass = "business",
        quantity = 1,
        amount = BigDecimal("45.50"),
        currency = "EUR",
        status = "RESERVED",
    )
}
