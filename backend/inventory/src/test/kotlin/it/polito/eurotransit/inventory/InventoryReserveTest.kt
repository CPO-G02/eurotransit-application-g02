package it.polito.eurotransit.inventory

import it.polito.eurotransit.inventory.dto.OrderFailedEvent
import it.polito.eurotransit.inventory.dto.ReserveRequest
import it.polito.eurotransit.inventory.entities.SeatEntity
import it.polito.eurotransit.inventory.exceptions.InsufficientSeatsException
import it.polito.eurotransit.inventory.repositories.ProcessedEventRepository
import it.polito.eurotransit.inventory.repositories.ProcessedRequestRepository
import it.polito.eurotransit.inventory.repositories.ReservationRepository
import it.polito.eurotransit.inventory.repositories.SeatRepository
import it.polito.eurotransit.inventory.service.InventoryService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.kafka.listener.auto-startup=false"],
)
class InventoryReserveTest @Autowired constructor(
    private val inventoryService: InventoryService,
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    private val processedRequestRepository: ProcessedRequestRepository,
    private val processedEventRepository: ProcessedEventRepository,
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

    // Concurrency goes through real sockets so the load genuinely overlaps on
    // the server, R2DBC pool included — not an in-process call to the bean.
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
    }

    @BeforeEach
    fun reset() = runBlocking {
        `when`(jwtDecoder.decode("test-token")).thenReturn(Mono.just(jwtWithAudience("inventory")))
        processedEventRepository.deleteAll()
        processedRequestRepository.deleteAll()
        reservationRepository.deleteAll()
        seatRepository.deleteAll()
    }

    private fun jwtWithAudience(audience: String): Jwt {
        return Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .audience(listOf(audience))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build()
    }

    private data class ReserveOutcome(val status: Int, val reservationId: String?)

    private suspend fun postReserve(
        idempotencyKey: String,
        trainId: String,
        seatClass: String,
        quantity: Int,
    ): ReserveOutcome =
        webClient.post().uri("/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "idempotency_key" to idempotencyKey,
                    "train_id" to trainId,
                    "seat_class" to seatClass,
                    "quantity" to quantity,
                ),
            )
            .awaitExchange { response ->
                val status = response.statusCode().value()
                val body = response.awaitBody<Map<String, Any>>()
                ReserveOutcome(status, body["reservation_id"] as String?)
            }

    @Test
    fun `reserve decrements availability and persists a reservation`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-A", seatClass = "standard", available = 10))

        val response = inventoryService.reserve(ReserveRequest("ord-1", "TR-A", "standard", 3))

        assertEquals("RESERVED", response.status)
        assertTrue(response.reservationId.startsWith("res-"))
        assertEquals(7, seatRepository.findByTrainIdAndSeatClass("TR-A", "standard")!!.available)

        val reservation = reservationRepository.findByReservationId(response.reservationId)!!
        assertEquals("RESERVED", reservation.status)
        assertEquals("ord-1", reservation.orderId)
        assertEquals(3, reservation.quantity)
    }

    @Test
    fun `reserve throws when not enough seats and leaves availability untouched`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-B", seatClass = "standard", available = 2))

        assertFailsWith<InsufficientSeatsException> {
            inventoryService.reserve(ReserveRequest("ord-2", "TR-B", "standard", 3))
        }

        assertEquals(2, seatRepository.findByTrainIdAndSeatClass("TR-B", "standard")!!.available)
    }

    @Test
    fun `reserve with a non-positive quantity never increases availability`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-N", seatClass = "standard", available = 5))

        assertFailsWith<InsufficientSeatsException> {
            inventoryService.reserve(ReserveRequest("ord-n", "TR-N", "standard", -3))
        }

        assertEquals(5, seatRepository.findByTrainIdAndSeatClass("TR-N", "standard")!!.available)
    }

    @Test
    fun `an insufficient-seats failure is not memoised and can be retried once seats free up`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-R", seatClass = "standard", available = 1))

        assertFailsWith<InsufficientSeatsException> {
            inventoryService.reserve(ReserveRequest("ord-retry", "TR-R", "standard", 2))
        }
        assertEquals(0, processedRequestRepository.count())

        seatRepository.release("TR-R", "standard", 1)
        val response = inventoryService.reserve(ReserveRequest("ord-retry", "TR-R", "standard", 2))

        assertEquals("RESERVED", response.status)
        assertEquals(0, seatRepository.findByTrainIdAndSeatClass("TR-R", "standard")!!.available)
    }

    @Test
    fun `10 concurrent reserves on 5 seats never oversell`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-C", seatClass = "business", available = 5))

        val outcomes = coroutineScope {
            (1..10).map { i -> async { postReserve("ord-$i", "TR-C", "business", 1) } }.awaitAll()
        }

        assertEquals(5, outcomes.count { it.status == 200 })
        assertEquals(5, outcomes.count { it.status == 409 })
        assertEquals(0, seatRepository.findByTrainIdAndSeatClass("TR-C", "business")!!.available)
        assertEquals(5, reservationRepository.count())
    }

    @Test
    fun `50 concurrent reserves of mixed quantities on 20 seats never oversell`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-L", seatClass = "standard", available = 20))

        val quantities = (1..50).associateWith { (it % 3) + 1 }
        val outcomes = coroutineScope {
            quantities.map { (i, quantity) ->
                async { i to postReserve("ord-load-$i", "TR-L", "standard", quantity) }
            }.awaitAll()
        }

        val granted = outcomes.filter { (_, outcome) -> outcome.status == 200 }
        val seatsGranted = granted.sumOf { (i, _) -> quantities.getValue(i) }
        val remaining = seatRepository.findByTrainIdAndSeatClass("TR-L", "standard")!!.available

        assertTrue(outcomes.all { (_, outcome) -> outcome.status == 200 || outcome.status == 409 })
        assertTrue(remaining >= 0, "availability went negative: $remaining")
        assertEquals(20 - seatsGranted, remaining)
        assertEquals(granted.size.toLong(), reservationRepository.count())
        assertEquals(
            seatsGranted,
            reservationRepository.findAll().toList().sumOf { it.quantity },
            "reservation rows must reconcile exactly with the seats taken off the counter",
        )
    }

    @Test
    fun `duplicate reserve with the same idempotency key reserves once and replays the reservation`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-I", seatClass = "business", available = 10))

        val first = postReserve("ord-dup", "TR-I", "business", 2)
        val second = postReserve("ord-dup", "TR-I", "business", 2)

        assertEquals(200, first.status)
        assertEquals(200, second.status)
        assertEquals(first.reservationId, second.reservationId)
        assertEquals(8, seatRepository.findByTrainIdAndSeatClass("TR-I", "business")!!.available)
        assertEquals(1, reservationRepository.count())
        assertEquals(1, processedRequestRepository.count())
    }

    @Test
    fun `10 concurrent reserves with the same idempotency key reserve exactly once`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-K", seatClass = "business", available = 10))

        val outcomes = coroutineScope {
            (1..10).map { async { postReserve("ord-race", "TR-K", "business", 2) } }.awaitAll()
        }

        assertTrue(outcomes.all { it.status == 200 })
        assertEquals(1, outcomes.mapNotNull { it.reservationId }.distinct().size)
        assertEquals(8, seatRepository.findByTrainIdAndSeatClass("TR-K", "business")!!.available)
        assertEquals(1, reservationRepository.count())
        assertEquals(1, processedRequestRepository.count())
    }

    @Test
    fun `compensation releases seats once and ignores redelivery`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-D", seatClass = "standard", available = 10))
        val response = inventoryService.reserve(ReserveRequest("ord-4", "TR-D", "standard", 4))
        assertEquals(6, seatRepository.findByTrainIdAndSeatClass("TR-D", "standard")!!.available)

        val event = OrderFailedEvent(
            eventId = "evt-4",
            orderId = "ord-4",
            reservationId = response.reservationId,
            reason = "payment_declined",
        )

        inventoryService.compensate(event)
        assertEquals(10, seatRepository.findByTrainIdAndSeatClass("TR-D", "standard")!!.available)
        assertEquals("RELEASED", reservationRepository.findByReservationId(response.reservationId)!!.status)

        // Redelivery of the same event must not release the seats a second time.
        inventoryService.compensate(event)
        assertEquals(10, seatRepository.findByTrainIdAndSeatClass("TR-D", "standard")!!.available)
        assertEquals(1, processedEventRepository.count())
    }

    @Test
    fun `compensation without an event_id still releases exactly once`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-U", seatClass = "standard", available = 10))
        val response = inventoryService.reserve(ReserveRequest("ord-u", "TR-U", "standard", 4))

        val event = OrderFailedEvent(eventId = null, orderId = "ord-u", reservationId = response.reservationId)

        inventoryService.compensate(event)
        inventoryService.compensate(event)

        assertEquals(10, seatRepository.findByTrainIdAndSeatClass("TR-U", "standard")!!.available)
        assertEquals(0, processedEventRepository.count())
    }

    @Test
    fun `compensation is a no-op when reservation_id is null`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-E", seatClass = "standard", available = 8))

        inventoryService.compensate(OrderFailedEvent(eventId = "evt-5", orderId = "ord-5", reservationId = null))

        assertEquals(8, seatRepository.findByTrainIdAndSeatClass("TR-E", "standard")!!.available)
    }

    // Block body (not `=`) so the test returns Unit: the WebTestClient chain
    // ends in a value, which JUnit 6 would otherwise silently refuse to run.
    @Test
    fun `POST reserve returns 200 with reservation_id`() {
        runBlocking { seatRepository.save(SeatEntity(trainId = "TR-G", seatClass = "business", available = 3)) }

        webTestClient.post().uri("/reserve")
            .bodyValue(
                mapOf(
                    "idempotency_key" to "ord-7",
                    "train_id" to "TR-G",
                    "seat_class" to "business",
                    "quantity" to 1,
                ),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("RESERVED")
            .jsonPath("$.reservation_id").exists()
    }

    @Test
    fun `POST reserve returns 409 INSUFFICIENT_SEATS when sold out`() {
        runBlocking { seatRepository.save(SeatEntity(trainId = "TR-F", seatClass = "standard", available = 1)) }

        webTestClient.post().uri("/reserve")
            .bodyValue(
                mapOf(
                    "idempotency_key" to "ord-6",
                    "train_id" to "TR-F",
                    "seat_class" to "standard",
                    "quantity" to 5,
                ),
            )
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.status").isEqualTo("INSUFFICIENT_SEATS")
    }
}
