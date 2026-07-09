package it.polito.eurotransit.orders.client

import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyString
import org.springframework.web.reactive.function.client.WebClient

class PaymentClientTest {

    @Test
    fun `fallbackPayment returns declined status when system is unavailable`() = runBlocking {
        val webClientBuilder = mock(WebClient.Builder::class.java)
        
        `when`(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder)
        `when`(webClientBuilder.build()).thenReturn(mock(WebClient::class.java))
        
        val client = PaymentClient(webClientBuilder, "http://localhost:8080")
        
        val request = mock(PaymentAuthorizeRequest::class.java)
        
        val error = RuntimeException("Service Down")
        val response = client.fallbackPayment(request, error)
        
        assertEquals("DECLINED", response.status)
        assertEquals("payment_system_unavailable", response.reason)
    }
}