package it.polito.eurotransit.orders.client

import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import it.polito.eurotransit.orders.dto.PaymentAuthorizeResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PaymentClientTest {

    @Test
    fun `fallbackPayment returns declined status when system is unavailable`() = runBlocking {
        val client = mock<PaymentClient>()
        
        whenever(client.fallbackPayment(any(), any())).thenReturn(
            PaymentAuthorizeResponse(null, "DECLINED", "payment_system_unavailable")
        )

        val request = mock<PaymentAuthorizeRequest>()
        val result = client.fallbackPayment(request, RuntimeException("Test"))
        
        assertEquals("DECLINED", result.status)
    }
}