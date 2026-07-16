package it.polito.eurotransit.orders

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import it.polito.eurotransit.orders.dto.OrderPlacedEvent
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import it.polito.eurotransit.orders.repositories.ProcessedRequestRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = [
        "spring.kafka.listener.auto-startup=false",
        "spring.jackson.default-property-inclusion=non_null",
    ],
)
class OrdersRepositoryPostgresTest @Autowired constructor(
    private val orderRepository: OrderRepository,
    private val processedRequestRepository: ProcessedRequestRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @BeforeEach
    fun reset() = runBlocking {
        processedEventRepository.deleteAll()
        processedRequestRepository.deleteAll()
        orderRepository.deleteAll()
    }

    @Test
    fun `insertNew inserts a String keyed order and rejects duplicate ids`() = runBlocking {
        val createdAt = LocalDateTime.now()

        val inserted = orderRepository.insertNew(
            orderId = "ord-real-db",
            userId = "user-1",
            userEmail = "client@example.com",
            trainId = "TR-101",
            seatClass = "business",
            quantity = 1,
            amount = BigDecimal("45.50"),
            currency = "EUR",
            status = "PENDING",
            transactionId = null,
            createdAt = createdAt,
            confirmedAt = null,
        )

        assertEquals(1, inserted)
        val saved = assertNotNull(orderRepository.findById("ord-real-db"))
        assertEquals("client@example.com", saved.userEmail)
        assertEquals("PENDING", saved.status)

        assertFailsWith<DataIntegrityViolationException> {
            orderRepository.insertNew(
                orderId = "ord-real-db",
                userId = "user-2",
                userEmail = "duplicate@example.com",
                trainId = "TR-102",
                seatClass = "standard",
                quantity = 2,
                amount = BigDecimal("50.00"),
                currency = "EUR",
                status = "PENDING",
                transactionId = null,
                createdAt = createdAt,
                confirmedAt = null,
            )
        }
        assertEquals(1, orderRepository.findAll().toList().size)
    }

    @Test
    fun `processed request insertIfAbsent returns one insert and one duplicate`() = runBlocking {
        assertEquals(1, processedRequestRepository.insertIfAbsent("idem-1", "ord-1"))
        assertEquals(0, processedRequestRepository.insertIfAbsent("idem-1", "ord-2"))

        val rows = processedRequestRepository.findAll().toList()
        assertEquals(1, rows.size)
        assertEquals("ord-1", rows.single().orderId)
    }

    @Test
    fun `processed event insertIfAbsent returns one insert and one duplicate`() = runBlocking {
        assertEquals(1, processedEventRepository.insertIfAbsent("evt-1"))
        assertEquals(0, processedEventRepository.insertIfAbsent("evt-1"))

        val rows = processedEventRepository.findAll().toList()
        assertEquals(1, rows.size)
        assertEquals("evt-1", rows.single().eventId)
    }

    @Test
    fun `concurrent processed request inserts allow exactly one winner`() = runBlocking {
        val results = concurrentPair(
            { processedRequestRepository.insertIfAbsent("idem-concurrent", "ord-a") },
            { processedRequestRepository.insertIfAbsent("idem-concurrent", "ord-b") },
        )

        assertEquals(listOf(0, 1), results.sorted())
        assertEquals(1, processedRequestRepository.findAll().toList().size)
    }

    @Test
    fun `concurrent processed event inserts allow exactly one winner`() = runBlocking {
        val results = concurrentPair(
            { processedEventRepository.insertIfAbsent("evt-concurrent") },
            { processedEventRepository.insertIfAbsent("evt-concurrent") },
        )

        assertEquals(listOf(0, 1), results.sorted())
        assertEquals(1, processedEventRepository.findAll().toList().size)
    }

    @Test
    fun `Spring Boot ObjectMapper serializes order placed with snake case contract fields`() {
        val json = objectMapper.writeValueAsString(
            OrderPlacedEvent(
                eventId = "evt-placed",
                eventTimestamp = "2026-07-15T10:00:00Z",
                orderId = "ord-1",
                trainId = "TR-101",
                seatClass = "business",
                quantity = 1,
            ),
        )
        val payload = objectMapper.readTree(json)

        assertEquals("evt-placed", payload["event_id"].asText())
        assertEquals("2026-07-15T10:00:00Z", payload["event_timestamp"].asText())
        assertEquals("ord-1", payload["order_id"].asText())
        assertEquals("TR-101", payload["train_id"].asText())
        assertEquals("business", payload["seat_class"].asText())
        assertEquals(1, payload["quantity"].asInt())
        assertEquals(false, payload.has("eventId"))
        assertEquals(false, payload.has("eventTimestamp"))
        assertEquals(false, payload.has("orderId"))
        assertEquals(false, payload.has("trainId"))
        assertEquals(false, payload.has("seatClass"))
    }

    @Test
    fun `application ObjectMapper keeps Boot settings plus Kotlin and Java time support`() {
        val value = ObjectMapperProbe(
            trainId = "TR-202",
            createdAt = LocalDateTime.of(2026, 7, 15, 10, 30, 45),
            nullableNote = null,
        )

        val json = objectMapper.writeValueAsString(value)
        val payload = objectMapper.readTree(json)

        assertEquals("TR-202", payload["train_id"].asText())
        assertEquals("2026-07-15T10:30:45", payload["created_at"].asText())
        assertFalse(payload.has("nullable_note"))
        assertFalse(objectMapper.serializationConfig.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS))

        val decoded = objectMapper.readValue<ObjectMapperProbe>(
            """
            {
              "train_id": "TR-303",
              "created_at": "2026-07-15T11:00:00"
            }
            """.trimIndent(),
        )

        assertEquals("TR-303", decoded.trainId)
        assertEquals(LocalDateTime.of(2026, 7, 15, 11, 0), decoded.createdAt)
        assertNull(decoded.nullableNote)
        assertTrue(decoded.kotlinDefaulted)
    }

    private suspend fun concurrentPair(first: suspend () -> Int, second: suspend () -> Int): List<Int> =
        coroutineScope {
            listOf(
                async { first() },
                async { second() },
            ).awaitAll()
        }

    private data class ObjectMapperProbe(
        val trainId: String,
        val createdAt: LocalDateTime,
        val nullableNote: String? = null,
        val kotlinDefaulted: Boolean = true,
    )
}
