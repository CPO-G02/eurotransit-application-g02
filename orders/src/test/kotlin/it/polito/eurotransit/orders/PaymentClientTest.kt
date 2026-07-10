package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

@WireMockTest(httpPort = 8089)
class PaymentClientTest {

    private lateinit var client: PaymentClient

    @BeforeEach
    fun setup() {
        val webClientBuilder = WebClient.builder()
        client = PaymentClient(webClientBuilder, "http://localhost:8089")
    }

    @Test
    fun `fallbackPayment returns declined status and reason when system is unavailable`() = runBlocking {
        stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(aResponse().withStatus(503))
        )

        val request = PaymentAuthorizeRequest(
            idempotency_key = "ord-123", 
            user_id = "user-1", 
            amount = BigDecimal("50.00"), 
            currency = "EUR"
        )
        
        val fallbackResult = client.fallbackPayment(request, RuntimeException("Simulated Network Error"))
        
        assertEquals("DECLINED", fallbackResult.status)
        assertEquals("payment_system_unavailable", fallbackResult.reason)
    }
}