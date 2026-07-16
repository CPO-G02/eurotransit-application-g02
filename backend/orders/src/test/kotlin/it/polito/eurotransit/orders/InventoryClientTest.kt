package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import it.polito.eurotransit.orders.client.ServiceTokenProvider
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import kotlin.system.measureTimeMillis

@WireMockTest(httpPort = 8081)
class InventoryClientTest {

    private fun client(timeout: Duration = Duration.ofSeconds(2), token: ServiceTokenProvider? = null) =
        InventoryClient(WebClient.builder(), "http://localhost:8081", inventoryTimeout = timeout, serviceTokenProvider = token)

    @Test
    fun `reserveSeats returns INSUFFICIENT_SEATS status on CONFLICT`() = runBlocking {
        val client = client()

        val request = InventoryReserveRequest(
            idempotency_key = "test-key",
            train_id = "T1",
            seat_class = "first",
            quantity = 1
        )

        stubFor(post(urlEqualTo("/reserve"))
            .willReturn(aResponse()
                .withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"status": "INSUFFICIENT_SEATS"}""")))

        val response = client.reserveSeats(request)

        assertEquals("INSUFFICIENT_SEATS", response.status)
    }

    @Test
    fun `reserveSeats aborts a hung inventory within the timeout budget`() = runBlocking {
        val client = client(timeout = Duration.ofMillis(300))

        val request = InventoryReserveRequest(
            idempotency_key = "test-key",
            train_id = "T1",
            seat_class = "first",
            quantity = 1
        )

        stubFor(post(urlEqualTo("/reserve"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"status": "RESERVED"}""")
                .withFixedDelay(3000)))

        val elapsed = measureTimeMillis {
            assertThrows(Exception::class.java) { runBlocking { client.reserveSeats(request) } }
        }
        assertTrue(elapsed < 2000, "call should have been cut at ~300ms, took ${elapsed}ms")
    }

    @Test
    fun `reserveSeats sends service account bearer token when enabled`() = runBlocking {
        val tokenProvider = ServiceTokenProvider(
            enabled = true,
            tokenUri = "http://localhost:8081/token",
            clientId = "orders-service",
            clientSecret = "test-secret",
            scope = "",
            issuerUri = "https://g02.cpo2026.it/auth/realms/eurotransit"
        )
        val client = client(token = tokenProvider)

        val request = InventoryReserveRequest(
            idempotency_key = "test-key",
            train_id = "T1",
            seat_class = "first",
            quantity = 1
        )

        stubFor(post(urlEqualTo("/token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"access_token":"service-token","expires_in":60}""")))
        stubFor(post(urlEqualTo("/reserve"))
            .withHeader("Authorization", equalTo("Bearer service-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"status": "RESERVED"}""")))

        val response = client.reserveSeats(request)

        assertEquals("RESERVED", response.status)
    }
}
