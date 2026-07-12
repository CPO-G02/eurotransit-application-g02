package it.polito.eurotransit.payments

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.gateway.GatewayDecision
import it.polito.eurotransit.payments.gateway.PaymentGateway
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Fast, test-tuned circuit-breaker settings so opening it takes few calls; the
// production values live in application.yaml. This validates the wiring: the
// breaker opens on failures AND on slow calls, and the fallback returns
// circuit_breaker_open.
@Testcontainers
@SpringBootTest(
    properties = [
        "resilience4j.circuitbreaker.instances.payment-gateway.sliding-window-size=5",
        "resilience4j.circuitbreaker.instances.payment-gateway.minimum-number-of-calls=5",
        "resilience4j.circuitbreaker.instances.payment-gateway.slow-call-duration-threshold=200ms",
        "app.gateway.timeout=2s",
    ],
)
class PaymentGatewayCircuitBreakerTest @Autowired constructor(
    private val paymentGateway: PaymentGateway,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
) {

    // Replaces the real decoder bean so context load does not reach Keycloak.
    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    private val breaker: CircuitBreaker
        get() = circuitBreakerRegistry.circuitBreaker("payment-gateway")

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        val gateway = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun startGateway() = gateway.start()

        @JvmStatic
        @AfterAll
        fun stopGateway() = gateway.stop()

        @JvmStatic
        @DynamicPropertySource
        fun gatewayUrl(registry: DynamicPropertyRegistry) {
            registry.add("app.gateway.url") { "http://localhost:${gateway.port()}" }
        }
    }

    @BeforeEach
    fun reset() {
        gateway.resetAll()
        breaker.reset()
    }

    private fun authorize() = runBlocking {
        paymentGateway.authorize(AuthorizeRequest("ord", "user-1", BigDecimal("120.00"), "EUR"))
    }

    @Test
    fun `opens on repeated gateway failures and falls back to circuit_breaker_open`() {
        gateway.stubFor(post(urlEqualTo("/gateway/charge")).willReturn(aResponse().withStatus(503)))

        repeat(5) { authorize() }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)

        val decision = authorize()
        assertTrue(decision is GatewayDecision.Declined && decision.reason == "circuit_breaker_open")
    }

    @Test
    fun `opens on slow calls above the slow-call threshold`() {
        // 500ms > slow-call-duration-threshold (200ms) but < timeout (2s):
        // completes and is recorded as slow, not as a timeout error.
        gateway.stubFor(
            post(urlEqualTo("/gateway/charge"))
                .willReturn(okJson("""{"decision":"AUTHORIZED"}""").withFixedDelay(500)),
        )

        repeat(5) { authorize() }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)

        val decision = authorize()
        assertTrue(decision is GatewayDecision.Declined && decision.reason == "circuit_breaker_open")
    }

    @Test
    fun `stays closed and passes the decision through when the gateway is healthy`() {
        gateway.stubFor(post(urlEqualTo("/gateway/charge")).willReturn(okJson("""{"decision":"AUTHORIZED"}""")))

        val decision = authorize()

        assertEquals(GatewayDecision.Authorized, decision)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
    }
}
