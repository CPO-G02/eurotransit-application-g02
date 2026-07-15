package it.polito.eurotransit.orders

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import it.polito.eurotransit.orders.kafka.Stage2Consumer
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "spring.kafka.listener.auto-startup=false",
        "app.security.service-token.enabled=false",
    ],
)
class OrdersStage2RollbackPostgresTest @Autowired constructor(
    private val stage2Consumer: Stage2Consumer,
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRepository,
    private val processedEventRepository: ProcessedEventRepository,
) {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

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
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.payments.url") { "http://localhost:${payments.port()}" }
        }
    }

    @BeforeEach
    fun reset() = runBlocking {
        payments.resetAll()
        outboxRepository.deleteAll()
        processedEventRepository.deleteAll()
        orderRepository.deleteAll()
    }

    @Test
    fun `stage2 rolls back processed marker and order status when outbox write fails`() = runBlocking {
        val orderId = "ord-stage2-rollback"
        val eventId = "evt-inventory-reserved-rollback"
        val conflictingOutboxEventId = "evt-$orderId-stage2"

        orderRepository.insertNew(
            orderId = orderId,
            userId = "user-rollback",
            userEmail = "rollback@example.com",
            trainId = "TR-rollback",
            seatClass = "business",
            quantity = 1,
            amount = BigDecimal("42.00"),
            currency = "EUR",
            status = "PENDING",
            transactionId = null,
            createdAt = LocalDateTime.of(2026, 7, 15, 12, 0),
            confirmedAt = null,
        )
        outboxRepository.insert(
            eventId = conflictingOutboxEventId,
            topic = "preexisting-topic",
            payload = """{"preexisting":true}""",
        )
        payments.stubFor(
            post(urlEqualTo("/authorize"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"transaction_id":"tx-rollback","status":"AUTHORIZED"}"""),
                ),
        )

        val message = """
            {
              "event_id": "$eventId",
              "event_timestamp": "2026-07-15T12:00:00Z",
              "order_id": "$orderId",
              "reservation_id": "res-rollback",
              "user_id": "user-rollback",
              "amount": 42.00,
              "currency": "EUR"
            }
        """.trimIndent()

        assertFailsWith<DataIntegrityViolationException> {
            stage2Consumer.consumeInventoryReserved(message)
        }

        val order = assertNotNull(orderRepository.findById(orderId))
        assertEquals("PENDING", order.status)
        assertEquals(null, processedEventRepository.findById(eventId))

        val outboxRows = outboxRepository.findAll().toList()
        assertEquals(1, outboxRows.size)
        assertEquals(conflictingOutboxEventId, outboxRows.single().eventId)
        assertEquals("preexisting-topic", outboxRows.single().topic)
        assertEquals("""{"preexisting":true}""", outboxRows.single().payload.replace(" ", ""))

        assertEquals(1, processedEventRepository.insertIfAbsent(eventId))
    }
}
