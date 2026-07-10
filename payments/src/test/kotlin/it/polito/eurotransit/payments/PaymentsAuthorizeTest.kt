package it.polito.eurotransit.payments

import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.exceptions.PaymentDeclinedException
import it.polito.eurotransit.payments.repositories.TransactionRepository
import it.polito.eurotransit.payments.service.PaymentsService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentsAuthorizeTest @Autowired constructor(
    private val paymentsService: PaymentsService,
    private val transactionRepository: TransactionRepository,
    @Value("\${local.server.port}") private val port: Int,
) {

    private val webTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @BeforeEach
    fun reset() = runBlocking {
        transactionRepository.deleteAll()
    }

    @Test
    fun `authorize below threshold is authorized and persisted`() = runBlocking {
        val response = paymentsService.authorize(
            AuthorizeRequest("ord-1", "user-1", BigDecimal("120.00"), "EUR"),
        )

        assertEquals("AUTHORIZED", response.status)
        assertTrue(response.transactionId.startsWith("txn-"))

        val transaction = transactionRepository.findByTransactionId(response.transactionId)!!
        assertEquals("AUTHORIZED", transaction.status)
        assertEquals("ord-1", transaction.orderId)
        assertEquals(0, transaction.amount.compareTo(BigDecimal("120.00")))
        assertEquals(null, transaction.reason)
    }

    @Test
    fun `authorize above threshold is declined and persisted with reason`() = runBlocking {
        assertFailsWith<PaymentDeclinedException> {
            paymentsService.authorize(
                AuthorizeRequest("ord-2", "user-1", BigDecimal("750.00"), "EUR"),
            )
        }

        val all = transactionRepository.findAll().toList()
        assertEquals(1, all.size)
        assertEquals("DECLINED", all.first().status)
        assertEquals("insufficient_funds", all.first().reason)
    }

    // Block body (not `=`) so the test returns Unit: the WebTestClient chain
    // ends in a value, which JUnit 6 would otherwise silently refuse to run.
    @Test
    fun `POST authorize returns 200 with transaction_id`() {
        webTestClient.post().uri("/api/v1/payments/authorize")
            .bodyValue(
                mapOf(
                    "idempotency_key" to "ord-3",
                    "user_id" to "user-1",
                    "amount" to 45.50,
                    "currency" to "EUR",
                ),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("AUTHORIZED")
            .jsonPath("$.transaction_id").exists()
    }

    @Test
    fun `POST authorize returns 402 DECLINED above threshold`() {
        webTestClient.post().uri("/api/v1/payments/authorize")
            .bodyValue(
                mapOf(
                    "idempotency_key" to "ord-4",
                    "user_id" to "user-1",
                    "amount" to 999.00,
                    "currency" to "EUR",
                ),
            )
            .exchange()
            .expectStatus().isEqualTo(402)
            .expectBody()
            .jsonPath("$.status").isEqualTo("DECLINED")
            .jsonPath("$.reason").isEqualTo("insufficient_funds")
    }
}
