package it.polito.eurotransit.inventory

import it.polito.eurotransit.inventory.dto.ReserveRequest
import it.polito.eurotransit.inventory.entities.SeatEntity
import it.polito.eurotransit.inventory.repositories.ProcessedEventRepository
import it.polito.eurotransit.inventory.repositories.ProcessedRequestRepository
import it.polito.eurotransit.inventory.repositories.ReservationRepository
import it.polito.eurotransit.inventory.repositories.SeatRepository
import it.polito.eurotransit.inventory.service.InventoryService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.test.assertEquals

// Drives compensation through the real @KafkaListener (the rest of the suite
// disables the listener). Single partition, so a trailing sentinel event is a
// barrier: once its effect is visible, everything published before it has been
// consumed — no asserting "nothing happened" against an arbitrary sleep.
@Testcontainers
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = ["eurotransit.order-failed"])
class OrderFailedConsumerDedupTest @Autowired constructor(
    private val inventoryService: InventoryService,
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    private val processedRequestRepository: ProcessedRequestRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val embeddedKafka: EmbeddedKafkaBroker,
) {

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private val kafkaTemplate by lazy {
        KafkaTemplate(
            DefaultKafkaProducerFactory<String, String>(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to embeddedKafka.brokersAsString,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ),
            ),
        )
    }

    @BeforeEach
    fun reset() = runBlocking {
        `when`(jwtDecoder.decode("test-token")).thenReturn(Mono.just(anyJwt()))
        processedEventRepository.deleteAll()
        processedRequestRepository.deleteAll()
        reservationRepository.deleteAll()
        seatRepository.deleteAll()
    }

    private fun anyJwt(): Jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .audience(listOf("inventory"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build()

    private fun publishOrderFailed(eventId: String, orderId: String, reservationId: String?) {
        val reservationField = reservationId?.let { """"reservation_id": "$it",""" } ?: ""
        val payload = """
            {
              "event_id": "$eventId",
              "event_timestamp": "${Instant.now()}",
              "order_id": "$orderId",
              $reservationField
              "reason": "insufficient_funds",
              "user_email": "user@example.com"
            }
        """.trimIndent()
        kafkaTemplate.send("eurotransit.order-failed", orderId, payload).get()
    }

    private suspend fun awaitAvailable(trainId: String, seatClass: String, expected: Int) {
        withTimeout(30_000) {
            while (seatRepository.findByTrainIdAndSeatClass(trainId, seatClass)?.available != expected) {
                delay(50)
            }
        }
    }

    @Test
    fun `a redelivered order-failed event releases the seats only once`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-KD", seatClass = "standard", available = 10))
        seatRepository.save(SeatEntity(trainId = "TR-BARRIER", seatClass = "standard", available = 10))
        val reservation = inventoryService.reserve(ReserveRequest("ord-kd", "TR-KD", "standard", 4))
        val barrier = inventoryService.reserve(ReserveRequest("ord-barrier", "TR-BARRIER", "standard", 1))

        publishOrderFailed("evt-kd-1", "ord-kd", reservation.reservationId)
        awaitAvailable("TR-KD", "standard", 10)

        // Kafka is at-least-once: the identical event, same event_id, arrives again.
        publishOrderFailed("evt-kd-1", "ord-kd", reservation.reservationId)
        publishOrderFailed("evt-barrier", "ord-barrier", barrier.reservationId)
        awaitAvailable("TR-BARRIER", "standard", 10)

        assertEquals(10, seatRepository.findByTrainIdAndSeatClass("TR-KD", "standard")!!.available)
        assertEquals("RELEASED", reservationRepository.findByReservationId(reservation.reservationId)!!.status)
        assertEquals(2, processedEventRepository.count(), "the redelivery must not add a processed_events row")
    }

    @Test
    fun `two distinct event_ids for the same reservation still release the seats only once`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-KE", seatClass = "business", available = 10))
        seatRepository.save(SeatEntity(trainId = "TR-BARRIER2", seatClass = "standard", available = 10))
        val reservation = inventoryService.reserve(ReserveRequest("ord-ke", "TR-KE", "business", 3))
        val barrier = inventoryService.reserve(ReserveRequest("ord-barrier2", "TR-BARRIER2", "standard", 1))

        // Distinct event_ids sail past processed_events; the status guard has
        // to stop the second release here.
        publishOrderFailed("evt-ke-1", "ord-ke", reservation.reservationId)
        publishOrderFailed("evt-ke-2", "ord-ke", reservation.reservationId)
        publishOrderFailed("evt-barrier2", "ord-barrier2", barrier.reservationId)
        awaitAvailable("TR-BARRIER2", "standard", 10)

        assertEquals(10, seatRepository.findByTrainIdAndSeatClass("TR-KE", "business")!!.available)
        assertEquals(3, processedEventRepository.count())
    }

    // The replayed event_id carries a *different*, still-RESERVED reservation,
    // so the status guard can't stop it — only the event_id gate can. This is
    // the one test that fails if the gate regresses while the guard holds.
    @Test
    fun `a replayed event_id is skipped before the handler runs`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-KG", seatClass = "standard", available = 10))
        seatRepository.save(SeatEntity(trainId = "TR-BARRIER4", seatClass = "standard", available = 10))
        val first = inventoryService.reserve(ReserveRequest("ord-kg-1", "TR-KG", "standard", 2))
        val second = inventoryService.reserve(ReserveRequest("ord-kg-2", "TR-KG", "standard", 3))
        val barrier = inventoryService.reserve(ReserveRequest("ord-barrier4", "TR-BARRIER4", "standard", 1))
        assertEquals(5, seatRepository.findByTrainIdAndSeatClass("TR-KG", "standard")!!.available)

        publishOrderFailed("evt-kg", "ord-kg-1", first.reservationId)
        awaitAvailable("TR-KG", "standard", 7)

        publishOrderFailed("evt-kg", "ord-kg-2", second.reservationId)
        publishOrderFailed("evt-barrier4", "ord-barrier4", barrier.reservationId)
        awaitAvailable("TR-BARRIER4", "standard", 10)

        assertEquals(7, seatRepository.findByTrainIdAndSeatClass("TR-KG", "standard")!!.available)
        assertEquals("RESERVED", reservationRepository.findByReservationId(second.reservationId)!!.status)
    }

    @Test
    fun `an order-failed event without a reservation_id is consumed and changes nothing`() = runBlocking {
        seatRepository.save(SeatEntity(trainId = "TR-KF", seatClass = "standard", available = 7))
        seatRepository.save(SeatEntity(trainId = "TR-BARRIER3", seatClass = "standard", available = 10))
        val barrier = inventoryService.reserve(ReserveRequest("ord-barrier3", "TR-BARRIER3", "standard", 1))

        // Stage 1's no-seats path: nothing was ever reserved, nothing to compensate.
        publishOrderFailed("evt-kf", "ord-kf", reservationId = null)
        publishOrderFailed("evt-barrier3", "ord-barrier3", barrier.reservationId)
        awaitAvailable("TR-BARRIER3", "standard", 10)

        assertEquals(7, seatRepository.findByTrainIdAndSeatClass("TR-KF", "standard")!!.available)
    }
}
