package it.polito.eurotransit.orders.client

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerConfigurationOnMissingBean
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryConfigurationOnMissingBean
import it.polito.eurotransit.orders.config.WebClientConfig
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [PaymentClientResilienceTest.TestApplication::class],
    properties = [
        "app.security.service-token.enabled=false",
        "resilience4j.timelimiter.instances.payments-client.timeout-duration=300ms",
        "resilience4j.circuitbreaker.instances.payments-client.sliding-window-size=2",
        "resilience4j.circuitbreaker.instances.payments-client.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.payments-client.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.payments-client.wait-duration-in-open-state=200ms",
        "resilience4j.circuitbreaker.instances.payments-client.permitted-number-of-calls-in-half-open-state=1",
        "resilience4j.circuitbreaker.instances.payments-client.automatic-transition-from-open-to-half-open-enabled=true",
    ],
)
class PaymentClientResilienceTest @Autowired constructor(
    private val paymentClient: PaymentClient,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
) {

    @SpringBootConfiguration
    @Import(
        PaymentClient::class,
        ServiceTokenProvider::class,
        WebClientConfig::class,
        CircuitBreakerAutoConfiguration::class,
        CircuitBreakerConfigurationOnMissingBean::class,
        RetryAutoConfiguration::class,
        RetryConfigurationOnMissingBean::class,
    )
    class TestApplication

    companion object {
        @JvmStatic
        val payments = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun startPayments() = payments.start()

        @JvmStatic
        @AfterAll
        fun stopPayments() = payments.stop()

        @JvmStatic
        @DynamicPropertySource
        fun paymentUrl(registry: DynamicPropertyRegistry) {
            registry.add("app.payments.url") { "http://localhost:${payments.port()}" }
        }
    }

    @BeforeEach
    fun reset() {
        payments.resetAll()
        circuitBreakerRegistry.circuitBreaker("payments-client").reset()
    }

    @Test
    fun `HTTP 200 authorized passes through the circuit breaker client`() = runBlocking {
        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"transaction_id":"tx-1","status":"AUTHORIZED"}"""),
                ),
        )

        val response = paymentClient.authorizePayment(request())

        assertEquals("AUTHORIZED", response.status)
        assertEquals("tx-1", response.transaction_id)
    }

    @Test
    fun `HTTP 402 declined is not converted to infrastructure fallback`() = runBlocking {
        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withFixedDelay(50)
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"status":"DECLINED","reason":"insufficient_funds"}"""),
                ),
        )

        val response = paymentClient.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("insufficient_funds", response.reason)
        assertNotEquals("payment_system_unavailable", response.reason)
        assertEquals(0, circuitBreakerRegistry.circuitBreaker("payments-client").metrics.numberOfFailedCalls)
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerRegistry.circuitBreaker("payments-client").state)
    }

    @Test
    fun `HTTP 5xx uses infrastructure fallback`() = runBlocking {
        payments.stubFor(post(urlEqualTo("/authorize")).willReturn(aResponse().withStatus(503)))

        val response = paymentClient.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("payment_system_unavailable", response.reason)
        assertEquals(1, circuitBreakerRegistry.circuitBreaker("payments-client").metrics.numberOfFailedCalls)
    }

    @Test
    fun `open circuit uses infrastructure fallback`() = runBlocking {
        circuitBreakerRegistry.circuitBreaker("payments-client").transitionToOpenState()

        val response = paymentClient.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("payment_system_unavailable", response.reason)
    }

    @Test
    fun `connection failure uses infrastructure fallback`() = runBlocking {
        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        val response = paymentClient.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("payment_system_unavailable", response.reason)
    }

    @Test
    fun `timeout uses infrastructure fallback`() = runBlocking {
        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withFixedDelay(2_000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"transaction_id":"tx-late","status":"AUTHORIZED"}"""),
                ),
        )

        val response = paymentClient.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("payment_system_unavailable", response.reason)
        assertEquals(1, circuitBreakerRegistry.circuitBreaker("payments-client").metrics.numberOfFailedCalls)
    }

    @Test
    fun `malformed HTTP 402 body uses deterministic infrastructure fallback`() = runBlocking {
        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"status":"""),
                ),
        )

        val response = paymentClient.authorizePayment(request())

        assertEquals("DECLINED", response.status)
        assertEquals("payment_system_unavailable", response.reason)
    }

    @Test
    fun `caller cancellation is not converted to payment failure`() = runBlocking {
        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withFixedDelay(2_000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"transaction_id":"tx-late","status":"AUTHORIZED"}"""),
                ),
        )

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) {
                paymentClient.authorizePayment(request())
            }
        }
        Unit
    }

    @Test
    fun `repeated timeout failures open then half-open and close after recovery`() = runBlocking {
        val breaker = circuitBreakerRegistry.circuitBreaker("payments-client")
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)

        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withFixedDelay(2_000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"transaction_id":"tx-late","status":"AUTHORIZED"}"""),
                ),
        )

        repeat(2) {
            val response = paymentClient.authorizePayment(request())
            assertEquals("DECLINED", response.status)
            assertEquals("payment_system_unavailable", response.reason)
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)

        awaitBreakerState(breaker, CircuitBreaker.State.HALF_OPEN)

        payments.resetAll()
        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"transaction_id":"tx-recovered","status":"AUTHORIZED"}"""),
                ),
        )

        val recovered = paymentClient.authorizePayment(request())

        assertEquals("AUTHORIZED", recovered.status)
        assertEquals("tx-recovered", recovered.transaction_id)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }

    private fun request() = PaymentAuthorizeRequest(
        idempotency_key = "ord-123",
        user_id = "user-1",
        amount = BigDecimal("50.00"),
        currency = "EUR",
    )

    private fun awaitBreakerState(breaker: CircuitBreaker, expectedState: CircuitBreaker.State) {
        repeat(20) {
            if (breaker.state == expectedState) return
            Thread.sleep(50)
        }
        assertTrue(false, "expected breaker state $expectedState but was ${breaker.state}")
    }
}
