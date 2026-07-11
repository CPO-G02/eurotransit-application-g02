package it.polito.eurotransit.inventory

import it.polito.eurotransit.inventory.dto.OrderFailedEvent
import it.polito.eurotransit.inventory.dto.ReserveRequest
import it.polito.eurotransit.inventory.entities.SeatEntity
import it.polito.eurotransit.inventory.exceptions.InsufficientSeatsException
import it.polito.eurotransit.inventory.repositories.ReservationRepository
import it.polito.eurotransit.inventory.repositories.SeatRepository
import it.polito.eurotransit.inventory.service.InventoryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        reservationRepository.deleteAll()
        seatRepository.deleteAll()
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
    fun `10 concurrent reserves on 5 seats never oversell`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-C", seatClass = "business", available = 5))

        val outcomes = (1..10).map { i ->
            async(Dispatchers.IO) {
                runCatching { inventoryService.reserve(ReserveRequest("ord-$i", "TR-C", "business", 1)) }
            }
        }.awaitAll()

        assertEquals(5, outcomes.count { it.isSuccess })
        assertEquals(5, outcomes.count { it.isFailure })
        assertEquals(0, seatRepository.findByTrainIdAndSeatClass("TR-C", "business")!!.available)
        assertEquals(5, reservationRepository.count())
    }

    @Test
    fun `compensation releases seats once and ignores redelivery`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-D", seatClass = "standard", available = 10))
        val response = inventoryService.reserve(ReserveRequest("ord-4", "TR-D", "standard", 4))
        assertEquals(6, seatRepository.findByTrainIdAndSeatClass("TR-D", "standard")!!.available)

        val event = OrderFailedEvent(orderId = "ord-4", reservationId = response.reservationId, reason = "payment_declined")

        inventoryService.compensate(event)
        assertEquals(10, seatRepository.findByTrainIdAndSeatClass("TR-D", "standard")!!.available)
        assertEquals("RELEASED", reservationRepository.findByReservationId(response.reservationId)!!.status)

        // Redelivery of the same event must not release the seats a second time.
        inventoryService.compensate(event)
        assertEquals(10, seatRepository.findByTrainIdAndSeatClass("TR-D", "standard")!!.available)
    }

    @Test
    fun `compensation is a no-op when reservation_id is null`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-E", seatClass = "standard", available = 8))

        inventoryService.compensate(OrderFailedEvent(orderId = "ord-5", reservationId = null))

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
