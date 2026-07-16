package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

// Proves the actual claim behind the fix: a hung downstream is now recorded as a
// circuit-breaker failure and the breaker opens after the threshold. This test
// fails against the old @CircuitBreaker annotation (aspect no-op on suspend
// functions -> breaker stays CLOSED), which is exactly why the clients switched
// to the programmatic executeSuspendFunction. The registry is built in code with
// the production application.yaml values; the yaml->registry binding is Spring
// Boot's and is exercised at app startup, not here.
class OrdersClientCircuitBreakerTest {

    private val registry = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .build()
    )

    private val reserveReq = InventoryReserveRequest("ord-1", "T1", "standard", 1)
    private val payReq = PaymentAuthorizeRequest("ord-1", "user-1", BigDecimal("50.00"), "EUR")

    companion object {
        private val server = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic @BeforeAll fun start() = server.start()
        @JvmStatic @AfterAll fun stop() = server.stop()
    }

    @BeforeEach
    fun reset() {
        server.resetAll()
        registry.allCircuitBreakers.forEach { it.reset() }
    }

    private fun inventoryClient(timeout: Duration) =
        InventoryClient(
            WebClient.builder(),
            "http://localhost:${server.port()}",
            inventoryTimeout = timeout,
            circuitBreakerRegistry = registry,
        )

    private fun paymentClient(timeout: Duration) =
        PaymentClient(
            WebClient.builder(),
            "http://localhost:${server.port()}",
            paymentsTimeout = timeout,
            circuitBreakerRegistry = registry,
        )

    @Test
    fun `a hung inventory is recorded as a failure and opens the breaker`() = runBlocking {
        server.stubFor(post(urlEqualTo("/reserve"))
            .willReturn(okJson("""{"status":"RESERVED"}""").withFixedDelay(2000)))
        val client = inventoryClient(Duration.ofMillis(300))
        val breaker = registry.circuitBreaker("inventory-client")

        repeat(5) {
            assertThrows(Exception::class.java) { runBlocking { client.reserveSeats(reserveReq) } }
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertEquals(5, breaker.metrics.numberOfFailedCalls) // timeouts WERE recorded
        assertThrows(CallNotPermittedException::class.java) {
            runBlocking { client.reserveSeats(reserveReq) } // 6th short-circuited
        }
        server.verify(5, postRequestedFor(urlEqualTo("/reserve")))
    }

    @Test
    fun `a hung payments returns the fallback and opens the breaker`() = runBlocking {
        server.stubFor(post(urlEqualTo("/authorize"))
            .willReturn(okJson("""{"status":"AUTHORIZED"}""").withFixedDelay(2000)))
        val client = paymentClient(Duration.ofMillis(300))
        val breaker = registry.circuitBreaker("payments-client")

        repeat(5) {
            val r = client.authorizePayment(payReq)
            assertEquals("DECLINED", r.status)
            assertEquals("payment_system_unavailable", r.reason)
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        val shorted = client.authorizePayment(payReq) // 6th short-circuited, still a safe fallback
        assertEquals("payment_system_unavailable", shorted.reason)
        server.verify(5, postRequestedFor(urlEqualTo("/authorize")))
    }

    @Test
    fun `a 402 decline is a business answer, not a breaker failure`() = runBlocking {
        server.stubFor(post(urlEqualTo("/authorize"))
            .willReturn(aResponse()
                .withStatus(402)
                .withHeader("Content-Type", "application/json")
                .withBody("""{"status":"DECLINED","reason":"insufficient_funds"}""")))
        val client = paymentClient(Duration.ofSeconds(6))
        val breaker = registry.circuitBreaker("payments-client")

        repeat(6) {
            val r = client.authorizePayment(payReq)
            assertEquals("DECLINED", r.status)
            assertEquals("insufficient_funds", r.reason) // the real reason, not masked as unavailable
        }

        assertEquals(0, breaker.metrics.numberOfFailedCalls) // declines never trip the breaker
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        server.verify(6, postRequestedFor(urlEqualTo("/authorize")))
    }
}
