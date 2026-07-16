package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InventoryClientResilienceTest {

    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry
    private lateinit var inventoryClient: InventoryClient

    private val breaker: CircuitBreaker
        get() = circuitBreakerRegistry.circuitBreaker("inventory-client")

    companion object {
        @JvmStatic
        val inventory = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun startInventory() = inventory.start()

        @JvmStatic
        @AfterAll
        fun stopInventory() = inventory.stop()
    }

    @BeforeEach
    fun reset() {
        inventory.resetAll()
        circuitBreakerRegistry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50f)
                .build(),
        )
        inventoryClient = InventoryClient(
            WebClient.builder(),
            "http://localhost:${inventory.port()}",
            inventoryTimeout = Duration.ofMillis(250),
            circuitBreakerRegistry = circuitBreakerRegistry,
        )
    }

    @Test
    fun `successful reservation before timeout succeeds and is counted by the circuit breaker`() = runBlocking {
        inventory.stubFor(
            post(urlEqualTo("/reserve"))
                .willReturn(
                    aResponse()
                        .withFixedDelay(50)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"reservation_id":"res-1","status":"RESERVED"}"""),
                ),
        )

        val response = inventoryClient.reserveSeats(request())

        assertEquals("RESERVED", response.status)
        assertEquals("res-1", response.reservation_id)
        assertEquals(1, breaker.metrics.numberOfSuccessfulCalls)
        assertEquals(0, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `slow reservation response exceeding timeout fails and is counted by the circuit breaker`() = runBlocking {
        inventory.stubFor(slowReservation())

        assertFailsWith<Exception> {
            inventoryClient.reserveSeats(request())
        }

        assertEquals(1, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `connection timeout style failure is bounded and counted by the circuit breaker`() = runBlocking {
        inventoryClient = InventoryClient(
            WebClient.builder(),
            "http://10.255.255.1:81",
            inventoryTimeout = Duration.ofMillis(250),
            circuitBreakerRegistry = circuitBreakerRegistry,
        )

        val elapsedMs = measureTimeMillis {
            assertFailsWith<Exception> {
                inventoryClient.reserveSeats(request())
            }
        }

        assertTrue(elapsedMs < 2_000, "connection failure should be bounded by the configured timeout")
        assertEquals(1, breaker.metrics.numberOfFailedCalls)
    }

    @Test
    fun `network partition simulated by a silent downstream delay becomes observable failure`() = runBlocking {
        inventory.stubFor(slowReservation())

        assertFailsWith<Exception> {
            inventoryClient.reserveSeats(request())
        }

        assertEquals(1, breaker.metrics.numberOfFailedCalls)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    private fun slowReservation() =
        post(urlEqualTo("/reserve"))
            .willReturn(
                aResponse()
                    .withFixedDelay(2_000)
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"reservation_id":"res-late","status":"RESERVED"}"""),
            )

    private fun request() = InventoryReserveRequest(
        idempotency_key = "ord-123",
        train_id = "TR-101",
        seat_class = "business",
        quantity = BigDecimal.ONE.toInt(),
    )
}
