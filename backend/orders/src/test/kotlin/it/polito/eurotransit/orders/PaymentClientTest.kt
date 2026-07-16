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

    @Test
    fun `authorizePayment treats HTTP 402 as declined business response`() = runBlocking {
        stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"status":"DECLINED","reason":"insufficient_funds"}"""),
                ),
        )

        val request = PaymentAuthorizeRequest(
            idempotency_key = "ord-123",
            user_id = "user-1",
            amount = BigDecimal("50.00"),
            currency = "EUR",
        )

        val response = client.authorizePayment(request)

        assertEquals("DECLINED", response.status)
        assertEquals("insufficient_funds", response.reason)
    }

    @Test
    fun `authorizePayment sends service account bearer token when enabled`() = runBlocking {
        val tokenProvider = ServiceTokenProvider(
            enabled = true,
            tokenUri = "http://localhost:8089/token",
            clientId = "orders-service",
            clientSecret = "test-secret",
            scope = "",
            issuerUri = "https://g02.cpo2026.it/auth/realms/eurotransit",
        )
        val client = PaymentClient(
            WebClient.builder(),
            "http://localhost:8089",
            serviceTokenProvider = tokenProvider,
        )

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
