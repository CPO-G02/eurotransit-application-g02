package it.polito.eurotransit.payments

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.exceptions.PaymentDeclinedException
import it.polito.eurotransit.payments.repositories.ProcessedRequestRepository
import it.polito.eurotransit.payments.repositories.TransactionRepository
import it.polito.eurotransit.payments.service.PaymentsService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.mockito.Mockito.`when`
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentsAuthorizeTest @Autowired constructor(
    private val paymentsService: PaymentsService,
    private val transactionRepository: TransactionRepository,
    private val processedRequestRepository: ProcessedRequestRepository,
    @Value("\${local.server.port}") private val port: Int,
) {

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    private val webTestClient by lazy {
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .defaultHeader("Authorization", "Bearer test-token")
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

    private fun request(orderId: String) =
        mapOf("idempotency_key" to orderId, "user_id" to "user-1", "amount" to 120.0, "currency" to "EUR")

    @Test
    fun `authorized gateway decision is persisted and returned`() = runBlocking {
        stubDecision("""{"decision":"AUTHORIZED"}""")

        val response = paymentsService.authorize(AuthorizeRequest("ord-1", "user-1", BigDecimal("120.00"), "EUR"))

        assertEquals("AUTHORIZED", response.status)
        assertTrue(response.transactionId.startsWith("txn-"))
        val transaction = transactionRepository.findByTransactionId(response.transactionId)!!
        assertEquals("AUTHORIZED", transaction.status)
        assertEquals("ord-1", transaction.orderId)
        assertEquals(null, transaction.reason)
    }

    @Test
    fun `declined gateway decision throws and is persisted with reason`() = runBlocking {
        stubDecision("""{"decision":"DECLINED","reason":"insufficient_funds"}""")

        assertFailsWith<PaymentDeclinedException> {
            paymentsService.authorize(AuthorizeRequest("ord-2", "user-1", BigDecimal("120.00"), "EUR"))
        }

        val all = transactionRepository.findAll().toList()
        assertEquals(1, all.size)
        assertEquals("DECLINED", all.first().status)
        assertEquals("insufficient_funds", all.first().reason)
    }

    // Block body (not `=`) so the test returns Unit: the WebTestClient chain ends
    // in a value, which JUnit 6 would otherwise silently refuse to run.
    @Test
    fun `POST authorize returns 200 with transaction_id`() {
        stubDecision("""{"decision":"AUTHORIZED"}""")

        webTestClient.post().uri("/api/v1/payments/authorize")
            .bodyValue(request("ord-3"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("AUTHORIZED")
            .jsonPath("$.transaction_id").exists()
    }

    @Test
    fun `POST authorize returns 402 DECLINED when gateway declines`() {
        stubDecision("""{"decision":"DECLINED","reason":"insufficient_funds"}""")

        webTestClient.post().uri("/api/v1/payments/authorize")
            .bodyValue(request("ord-4"))
            .exchange()
            .expectStatus().isEqualTo(402)
            .expectBody()
            .jsonPath("$.status").isEqualTo("DECLINED")
            .jsonPath("$.reason").isEqualTo("insufficient_funds")
    }
}
