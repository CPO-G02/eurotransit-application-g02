package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import it.polito.eurotransit.orders.client.ServiceTokenProvider
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

@WireMockTest(httpPort = 8081)
class InventoryClientTest {

    @Test
    fun `reserveSeats returns INSUFFICIENT_SEATS status on CONFLICT`() = runBlocking {
        val client = InventoryClient(WebClient.builder(), "http://localhost:8081")
        
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
    fun `reserveSeats sends service account bearer token when enabled`() = runBlocking {
        val tokenProvider = ServiceTokenProvider(
            enabled = true,
            tokenUri = "http://localhost:8081/token",
            clientId = "orders-service",
            clientSecret = "test-secret",
            scope = ""
        )
        val client = InventoryClient(WebClient.builder(), "http://localhost:8081", tokenProvider)

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
