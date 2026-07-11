package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockTest
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
}