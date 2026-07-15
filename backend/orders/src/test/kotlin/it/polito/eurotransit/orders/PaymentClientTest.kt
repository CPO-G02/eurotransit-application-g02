package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

@WireMockTest(httpPort = 8089)
class PaymentClientTest {

    private lateinit var client: PaymentClient

    private fun client(token: ServiceTokenProvider? = null) =
        PaymentClient(WebClient.builder(), CircuitBreakerRegistry.ofDefaults(), "http://localhost:8089", Duration.ofSeconds(6), token)

    @BeforeEach
    fun setup() {
        client = client()
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

    @Test
    fun `authorizePayment sends service account bearer token when enabled`() = runBlocking {
        val tokenProvider = ServiceTokenProvider(
            enabled = true,
            tokenUri = "http://localhost:8089/token",
            clientId = "orders-service",
            clientSecret = "test-secret",
            scope = "",
        )
        val client = client(tokenProvider)

        val request = PaymentAuthorizeRequest(
            idempotency_key = "ord-123",
            user_id = "user-1",
            amount = BigDecimal("50.00"),
            currency = "EUR",
        )

        stubFor(
            post(urlEqualTo("/token"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"access_token":"service-token","expires_in":60}"""),
                ),
        )
        stubFor(
            post(urlEqualTo("/authorize"))
                .withHeader("Authorization", equalTo("Bearer service-token"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"transaction_id":"txn-1","status":"AUTHORIZED"}"""),
                ),
        )

        val response = client.authorizePayment(request)

        assertEquals("AUTHORIZED", response.status)
    }
}
