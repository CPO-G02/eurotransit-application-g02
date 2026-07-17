package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.WebClientRequestException
import com.github.tomakehurst.wiremock.http.Fault
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InventoryClientRetryTest {

    companion object {
        @JvmStatic
        val server = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun start() = server.start()

        @JvmStatic
        @AfterAll
        fun stop() = server.stop()
    }

    private lateinit var cbRegistry: CircuitBreakerRegistry
    private lateinit var retryRegistry: RetryRegistry
    private lateinit var client: InventoryClient

    private fun buildRetryRegistry(maxAttempts: Int = 3): RetryRegistry {
        val cfg = RetryConfig.custom<Any>()
            .maxAttempts(maxAttempts)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(10), 2.0))
            .retryOnException(::isClientRetryableException)
            .build()
        return RetryRegistry.of(cfg)
    }

    @BeforeEach
    fun setup() {
        server.resetAll()
        cbRegistry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .build(),
        )
        retryRegistry = buildRetryRegistry(maxAttempts = 3)
        client = InventoryClient(
            WebClient.builder(),
            "http://localhost:${server.port()}",
            inventoryTimeout = Duration.ofMillis(300),
            circuitBreakerRegistry = cbRegistry,
            retryRegistry = retryRegistry,
        )
    }

    @Test
    fun `transient 503 is retried max-attempts times before propagating`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/reserve"))
                .willReturn(aResponse().withStatus(503)),
        )

        assertFailsWith<Exception> {
            client.reserveSeats(request())
        }

        server.verify(3, postRequestedFor(urlEqualTo("/reserve")))
    }

    @Test
    fun `success after one transient 503 returns the reservation`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/reserve"))
                .inScenario("transient-then-ok")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"),
        )
        server.stubFor(
            post(urlEqualTo("/reserve"))
                .inScenario("transient-then-ok")
                .whenScenarioStateIs("ok")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"reservation_id":"res-1","status":"RESERVED"}"""),
                ),
        )

        val response = client.reserveSeats(request())

        assertEquals("RESERVED", response.status)
        assertEquals("res-1", response.reservation_id)
        server.verify(2, postRequestedFor(urlEqualTo("/reserve")))
    }

    @Test
    fun `business error 409 INSUFFICIENT_SEATS is not retried`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/reserve"))
                .willReturn(
                    aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"status":"INSUFFICIENT_SEATS"}"""),
                ),
        )

        val response = client.reserveSeats(request())

        assertEquals("INSUFFICIENT_SEATS", response.status)
        server.verify(1, postRequestedFor(urlEqualTo("/reserve")))
    }

    @Test
    fun `CallNotPermittedException from open breaker is not retried`() = runBlocking {
        cbRegistry.circuitBreaker("inventory-client").transitionToOpenState()

        assertFailsWith<CallNotPermittedException> {
            client.reserveSeats(request())
        }

        server.verify(0, postRequestedFor(urlEqualTo("/reserve")))
    }

    @Test
    fun `same idempotency_key is sent on every retry attempt`() = runBlocking {
        val key = "ord-idem-42"
        server.stubFor(
            post(urlEqualTo("/reserve"))
                .willReturn(aResponse().withStatus(503)),
        )

        assertFailsWith<Exception> {
            client.reserveSeats(
                InventoryReserveRequest(
                    idempotency_key = key,
                    train_id = "TR-101",
                    seat_class = "standard",
                    quantity = 2,
                ),
            )
        }

        val requests = server.allServeEvents
            .map { it.request.bodyAsString }
            .filter { it.contains(key) }
        assertEquals(3, requests.size)
    }

    @Test
    fun `retry config exposes expected backoff parameters`() {
        val cfg = retryRegistry.retry("inventory-client").retryConfig

        assertEquals(3, cfg.maxAttempts)
        assertTrue(cfg.exceptionPredicate.test(
            WebClientRequestException(
                java.io.IOException("Connection reset"),
                org.springframework.http.HttpMethod.POST,
                java.net.URI.create("http://localhost"),
                org.springframework.http.HttpHeaders.EMPTY
            )
        ))
        assertTrue(cfg.exceptionPredicate.test(
            WebClientResponseException.create(
                503, "Service Unavailable",
                org.springframework.http.HttpHeaders.EMPTY,
                byteArrayOf(),
                null
            )
        ))
        kotlin.test.assertFalse(cfg.exceptionPredicate.test(
            WebClientResponseException.create(
                409, "Conflict",
                org.springframework.http.HttpHeaders.EMPTY,
                byteArrayOf(),
                null
            )
        ))

        val intervalFn = cfg.intervalFunction!!
        val t1 = intervalFn.apply(1)
        val t2 = intervalFn.apply(2)
        assertTrue(t2 >= t1)
        assertTrue(t1 > 0)
    }

    @Test
    fun `each retry attempt is a separate CB call and opens the breaker after threshold`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/reserve"))
                .willReturn(aResponse().withStatus(503)),
        )

        assertFailsWith<Exception> {
            client.reserveSeats(request())
        }

        val cb = cbRegistry.circuitBreaker("inventory-client")
        assertEquals(3, cb.metrics.numberOfFailedCalls)
        assertEquals(
            io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN,
            cb.state,
        )
    }

    @Test
    fun `a response timeout is retried`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/reserve")).willReturn(
                aResponse().withFixedDelay(2_000).withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"reservation_id":"res-1","status":"RESERVED"}"""),
            ),
        )

        assertFailsWith<Exception> {
            client.reserveSeats(request()) // client timeout is 300ms
        }

        server.verify(3, postRequestedFor(urlEqualTo("/reserve")))
    }

    @Test
    fun `a connection failure is retried`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/reserve"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        assertFailsWith<Exception> {
            client.reserveSeats(request())
        }

        server.verify(3, postRequestedFor(urlEqualTo("/reserve")))
    }

    @Test
    fun `retry events are emitted by the registered instance`() = runBlocking {
        val retry = retryRegistry.retry("inventory-client")
        val events = java.util.concurrent.CopyOnWriteArrayList<io.github.resilience4j.retry.event.RetryEvent>()
        retry.eventPublisher.onEvent { event ->
            events.add(event)
        }

        server.stubFor(
            post(urlEqualTo("/reserve"))
                .willReturn(aResponse().withStatus(503)),
        )

        assertFailsWith<Exception> {
            client.reserveSeats(request())
        }

        assertTrue(events.isNotEmpty(), "Events should be emitted by the registered Retry instance")
        val retryEvents = events.filter { it.eventType == io.github.resilience4j.retry.event.RetryEvent.Type.RETRY }
        assertEquals(2, retryEvents.size, "Should observe exactly 2 retry events")
        val errorEvents = events.filter { it.eventType == io.github.resilience4j.retry.event.RetryEvent.Type.ERROR }
        assertEquals(1, errorEvents.size, "Should observe exactly 1 error event")
    }

    private fun request() = InventoryReserveRequest(
        idempotency_key = "ord-123",
        train_id = "TR-101",
        seat_class = "standard",
        quantity = 1,
    )
}
