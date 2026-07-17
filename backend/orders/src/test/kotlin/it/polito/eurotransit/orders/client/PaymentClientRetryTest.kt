package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentClientRetryTest {

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
    private lateinit var client: PaymentClient

    private fun buildRetryRegistry(maxAttempts: Int = 3): RetryRegistry {
        val cfg = RetryConfig.custom<Any>()
            .maxAttempts(maxAttempts)
            .intervalFunction(
                IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(10), 2.0, 0.5),
            )
            .retryOnException { t: Throwable ->
                when {
                    t is io.github.resilience4j.circuitbreaker.CallNotPermittedException -> false
                    t is kotlinx.coroutines.CancellationException -> false
                    t is java.io.IOException -> true
                    t is java.util.concurrent.TimeoutException -> true
                    t is WebClientResponseException && t.statusCode.is5xxServerError -> true
                    else -> false
                }
            }
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
        client = PaymentClient(
            WebClient.builder(),
            "http://localhost:${server.port()}",
            paymentsTimeout = Duration.ofMillis(300),
            circuitBreakerRegistry = cbRegistry,
            retryRegistry = retryRegistry,
        )
    }

    @Test
    fun `transient 503 is retried max-attempts times then returns fallback`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(aResponse().withStatus(503)),
        )

        val response = client.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("payment_system_unavailable", response.reason)
        server.verify(3, postRequestedFor(urlEqualTo("/authorize")))
    }

    @Test
    fun `success after one transient 503 returns AUTHORIZED`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/authorize"))
                .inScenario("transient-then-ok")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"),
        )
        server.stubFor(
            post(urlEqualTo("/authorize"))
                .inScenario("transient-then-ok")
                .whenScenarioStateIs("ok")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"transaction_id":"tx-recovered","status":"AUTHORIZED"}"""),
                ),
        )

        val response = client.authorizePayment(request())

        assertEquals("AUTHORIZED", response.status)
        assertEquals("tx-recovered", response.transaction_id)
        server.verify(2, postRequestedFor(urlEqualTo("/authorize")))
    }

    @Test
    fun `business error 402 is not retried and returns the real decline reason`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"status":"DECLINED","reason":"insufficient_funds"}"""),
                ),
        )

        val response = client.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("insufficient_funds", response.reason)
        server.verify(1, postRequestedFor(urlEqualTo("/authorize")))
        assertEquals(0, cbRegistry.circuitBreaker("payments-client").metrics.numberOfFailedCalls)
    }

    @Test
    fun `CallNotPermittedException from open breaker is not retried and returns fallback`() = runBlocking {
        cbRegistry.circuitBreaker("payments-client").transitionToOpenState()

        val response = client.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("payment_system_unavailable", response.reason)
        server.verify(0, postRequestedFor(urlEqualTo("/authorize")))
    }

    @Test
    fun `same idempotency_key is sent on every retry attempt`() = runBlocking {
        val key = "ord-idem-pay-42"
        server.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(aResponse().withStatus(503)),
        )

        val response = client.authorizePayment(
            PaymentAuthorizeRequest(
                idempotency_key = key,
                user_id = "user-1",
                amount = BigDecimal("50.00"),
                currency = "EUR",
            ),
        )

        assertEquals("DECLINED", response.status)
        val requests = server.allServeEvents
            .map { it.request.bodyAsString }
            .filter { it.contains(key) }
        assertEquals(3, requests.size)
    }

    @Test
    fun `retry config exposes expected backoff parameters including randomized wait`() {
        val cfg = retryRegistry.retry("payments-client").retryConfig

        assertEquals(3, cfg.maxAttempts)

        val intervalFn = cfg.intervalFunction!!
        val t1 = intervalFn.apply(1)
        val t2 = intervalFn.apply(2)
        assertTrue(t1 > 0)
        assertTrue(t2 > 0)
        assertTrue(t2 <= 60)
    }

    @Test
    fun `each retry attempt is a separate CB call and opens the breaker after threshold`() = runBlocking {
        server.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(aResponse().withStatus(503)),
        )

        val response = client.authorizePayment(request())

        assertEquals("DECLINED", response.status)

        val cb = cbRegistry.circuitBreaker("payments-client")
        assertEquals(3, cb.metrics.numberOfFailedCalls)
        assertEquals(CircuitBreaker.State.OPEN, cb.state)
    }

    private fun request() = PaymentAuthorizeRequest(
        idempotency_key = "ord-123",
        user_id = "user-1",
        amount = BigDecimal("50.00"),
        currency = "EUR",
    )
}
