package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.web.reactive.function.client.WebClient

@WireMockTest(httpPort = 8081)
class InventoryClientTest {

    @Test
    fun `reserveSeats returns INSUFFICIENT_SEATS status on CONFLICT`() = runBlocking {
        // 1. Instanciem el WebClient REAL apuntant a WireMock (Adéu als NullPointerExceptions!)
        val realWebClientBuilder = WebClient.builder()
        val client = InventoryClient(realWebClientBuilder, "http://localhost:8081")
        
        // 2. Mockegem la request per no haver d'endevinar els camps obligatoris
        val request = mock(InventoryReserveRequest::class.java)
        
        // 3. Aixequem un servidor fals (WireMock) que simuli la caiguda de l'inventari
        // Retornarem un error 409 (CONFLICT) com espera exactament el teu try-catch
        stubFor(post(urlEqualTo("/reserve"))
            .willReturn(aResponse()
                .withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"status": "INSUFFICIENT_SEATS"}""")))
        
        // 4. Cridem al teu mètode (el servidor fals retornarà l'error i el teu catch el capturarà)
        val response = client.reserveSeats(request)
        
        // 5. Comprovem que el teu catch ha funcionat a la perfecció
        assertEquals("INSUFFICIENT_SEATS", response.status)
    }
}