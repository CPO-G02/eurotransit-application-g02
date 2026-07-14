package it.polito.eurotransit.payments

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.exceptions.PaymentDeclinedException
import it.polito.eurotransit.payments.gateway.CIRCUIT_BREAKER_OPEN
import it.polito.eurotransit.payments.repositories.ProcessedRequestRepository
import it.polito.eurotransit.payments.repositories.TransactionRepository
import it.polito.eurotransit.payments.service.PaymentsService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Level 2 dedup (contract §3.2): a retried /authorize must replay the original
// decision instead of reaching the gateway again.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentsIdempotencyTest @Autowired constructor(
    private val paymentsService: PaymentsService,
    private val transactionRepository: TransactionRepository,
    private val processedRequestRepository: ProcessedRequestRepository,
    @Value("\${local.server.port}") private val port: Int,
) {

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    private val webClient by lazy {
        WebClient.builder()
            .baseUrl("http://localhost:$port")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
            .build()
    }

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
    fun reset() = runBlocking {
        `when`(jwtDecoder.decode("test-token")).thenReturn(Mono.just(jwtWithAudience("payments")))
        processedRequestRepository.deleteAll()
        transactionRepository.deleteAll()
        gateway.resetAll()
    }

    private fun jwtWithAudience(audience: String): Jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .audience(listOf(audience))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build()

    private fun stubDecision(json: String) {
        gateway.stubFor(post(urlEqualTo("/gateway/charge")).willReturn(okJson(json)))
    }

    private fun gatewayCallCount() =
        gateway.countRequestsMatching(postRequestedFor(urlEqualTo("/gateway/charge")).build()).count

    private data class AuthorizeOutcome(val status: Int, val transactionId: String?)

    private suspend fun postAuthorize(orderId: String): AuthorizeOutcome =
        webClient.post().uri("/api/v1/payments/authorize")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "idempotency_key" to orderId,
                    "user_id" to "user-1",
                    "amount" to 120.0,
                    "currency" to "EUR",
                ),
            )
            .awaitExchange { response ->
                val status = response.statusCode().value()
                val body = response.awaitBody<Map<String, Any>>()
                AuthorizeOutcome(status, body["transaction_id"] as String?)
            }

    @Test
    fun `a retried authorize replays the original decision without calling the gateway again`() = runBlocking {
        stubDecision("""{"decision":"AUTHORIZED"}""")
        val request = AuthorizeRequest("ord-idem-1", "user-1", BigDecimal("120.00"), "EUR")

        val first = paymentsService.authorize(request)
        val retry = paymentsService.authorize(request)

        assertEquals(first.transactionId, retry.transactionId)
        assertEquals("AUTHORIZED", retry.status)
        assertEquals(1, gatewayCallCount(), "the retry must not reach the gateway a second time")
        assertEquals(1, transactionRepository.findAll().toList().size)
        assertEquals(1, processedRequestRepository.count())
    }

    @Test
    fun `a retried authorize replays a decline without calling the gateway again`() = runBlocking {
        stubDecision("""{"decision":"DECLINED","reason":"insufficient_funds"}""")
        val request = AuthorizeRequest("ord-idem-2", "user-1", BigDecimal("120.00"), "EUR")

        assertFailsWith<PaymentDeclinedException> { paymentsService.authorize(request) }
        val replayed = assertFailsWith<PaymentDeclinedException> { paymentsService.authorize(request) }

        assertEquals("insufficient_funds", replayed.reason)
        assertEquals(1, gatewayCallCount())
        assertEquals(1, transactionRepository.findAll().toList().size)
    }

    // Two in-flight duplicates may both reach the gateway (its own
    // Idempotency-Key covers that); what must never happen is two rows.
    @Test
    fun `10 concurrent duplicate authorizes record exactly one transaction`() = runBlocking {
        stubDecision("""{"decision":"AUTHORIZED"}""")

        val outcomes = coroutineScope {
            (1..10).map { async { postAuthorize("ord-idem-race") } }.awaitAll()
        }

        assertTrue(outcomes.all { it.status == 200 }, "statuses were ${outcomes.map { it.status }}")
        assertEquals(
            1,
            outcomes.mapNotNull { it.transactionId }.distinct().size,
            "every caller must be handed the same transaction_id",
        )
        assertEquals(1, transactionRepository.findAll().toList().size)
        assertEquals(1, processedRequestRepository.count())
    }

    // Memoising an undecided call would pin the outage onto the order forever:
    // the retry below would never reach the recovered gateway.
    @Test
    fun `a circuit_breaker_open decline is not memoised and a later retry reaches the gateway`() = runBlocking {
        gateway.stubFor(post(urlEqualTo("/gateway/charge")).willReturn(aResponse().withStatus(500)))
        val request = AuthorizeRequest("ord-idem-cb", "user-1", BigDecimal("120.00"), "EUR")

        val declined = assertFailsWith<PaymentDeclinedException> { paymentsService.authorize(request) }
        assertEquals(CIRCUIT_BREAKER_OPEN, declined.reason)
        assertEquals(0, processedRequestRepository.count(), "an undecided call must not claim the key")

        stubDecision("""{"decision":"AUTHORIZED"}""")
        val retry = paymentsService.authorize(request)

        assertEquals("AUTHORIZED", retry.status)
        assertEquals(1, processedRequestRepository.count())
        assertEquals(2, gatewayCallCount(), "the retry must be allowed through to the recovered gateway")
    }
}
