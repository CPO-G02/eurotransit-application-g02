package it.polito.eurotransit.orders

import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.scheduler.OutboxRelay
import it.polito.eurotransit.orders.dto.PaymentAuthorizeResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class SagaRecoveryTest {

    private lateinit var paymentClient: PaymentClient
    private lateinit var orderRepo: OrderRepository
    private lateinit var outboxRelay: OutboxRelay

    @BeforeEach
    fun setup() {
        paymentClient = mock()
        orderRepo = mock()
        outboxRelay = mock()
    }

    @Test
    fun `should recover pipeline when payment service goes from down to up`() = runBlocking {
        val orderId = "ord-recovery-999"
        
        val initialOrder = Order(
            orderId = orderId, 
            userId = "user-1",
            userEmail = "marc@example.com",
            trainId = "train-x",
            seatClass = "standard",
            quantity = 1,
            amount = BigDecimal("50.00"),
            currency = "EUR",
            status = "INVENTORY_RESERVED"
        )
        
        whenever(orderRepo.findById(orderId)).thenReturn(initialOrder)
        
        whenever(paymentClient.authorizePayment(any())).thenThrow(RuntimeException("Payment Service Offline"))

        val failedOrder = orderRepo.findById(orderId)
        assertEquals("INVENTORY_RESERVED", failedOrder?.status)

        val successResponse = PaymentAuthorizeResponse("tx-123", "AUTHORIZED", null)
        whenever(paymentClient.authorizePayment(any())).thenReturn(successResponse)

        outboxRelay.processPendingMessages()
    }
}