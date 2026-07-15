package it.polito.eurotransit.inventory.service

import it.polito.eurotransit.inventory.dto.OrderFailedEvent
import it.polito.eurotransit.inventory.dto.ReserveRequest
import it.polito.eurotransit.inventory.dto.ReserveResponse
import it.polito.eurotransit.inventory.entities.ReservationEntity
import it.polito.eurotransit.inventory.exceptions.InsufficientSeatsException
import it.polito.eurotransit.inventory.repositories.ProcessedEventRepository
import it.polito.eurotransit.inventory.repositories.ProcessedRequestRepository
import it.polito.eurotransit.inventory.repositories.ReservationRepository
import it.polito.eurotransit.inventory.repositories.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DefaultInventoryService(
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    private val processedRequestRepository: ProcessedRequestRepository,
    private val processedEventRepository: ProcessedEventRepository,
) : InventoryService {

    private val log = LoggerFactory.getLogger(javaClass)

    // The claim precedes the decrement: a duplicate's ON CONFLICT blocks until
    // the winner commits, then reads back its reservation. A 409 rolls the claim
    // back — insufficient seats must not be memoised, they can free up later.
    @Transactional
    override suspend fun reserve(request: ReserveRequest): ReserveResponse {
        val reservationId = "res-${UUID.randomUUID()}"

        if (processedRequestRepository.insertIfAbsent(request.idempotencyKey, reservationId) == 0) {
            val original = processedRequestRepository.findById(request.idempotencyKey)
                ?: error("processed_requests row vanished for key ${request.idempotencyKey}")
            log.info(
                "event=reserve_deduplicated order_id={} reservation_id={}",
                request.idempotencyKey, original.reservationId,
            )
            return ReserveResponse(reservationId = original.reservationId, status = "RESERVED")
        }

        val reserved = seatRepository.reserve(request.trainId, request.seatClass, request.quantity)
        if (reserved == 0) {
            throw InsufficientSeatsException(request.trainId, request.seatClass, request.quantity)
        }

        reservationRepository.save(
            ReservationEntity(
                reservationId = reservationId,
                orderId = request.idempotencyKey,
                trainId = request.trainId,
                seatClass = request.seatClass,
                quantity = request.quantity,
                status = "RESERVED",
            ),
        )
        log.info(
            "event=seats_reserved reservation_id={} order_id={} train_id={} seat_class={} quantity={}",
            reservationId, request.idempotencyKey, request.trainId, request.seatClass, request.quantity,
        )
        return ReserveResponse(reservationId = reservationId, status = "RESERVED")
    }

    // event_id is claimed in the same transaction as the release it guards;
    // the RESERVED -> RELEASED transition below is an independent second layer.
    @Transactional
    override suspend fun compensate(event: OrderFailedEvent) {
        val reservationId = event.reservationId ?: return

        val eventId = event.eventId
        if (eventId == null) {
            // Producer bug (the contract guarantees event_id): releasing
            // anyway beats leaking the seats forever.
            log.warn("event=compensation_undeduplicated reason=missing_event_id reservation_id={}", reservationId)
        } else if (processedEventRepository.insertIfAbsent(eventId) == 0) {
            log.info("event=compensation_skipped reason=duplicate_event event_id={}", eventId)
            return
        }

        val reservation = reservationRepository.findByReservationId(reservationId)
        if (reservation == null) {
            log.warn("event=compensation_skipped reason=unknown_reservation reservation_id={}", reservationId)
            return
        }
        if (reservationRepository.markReleased(reservationId) == 0) {
            log.info("event=compensation_skipped reason=already_released reservation_id={}", reservationId)
            return
        }

        seatRepository.release(reservation.trainId, reservation.seatClass, reservation.quantity)
        log.info(
            "event=seats_released reservation_id={} order_id={} train_id={} seat_class={} quantity={}",
            reservationId, reservation.orderId, reservation.trainId, reservation.seatClass, reservation.quantity,
        )
    }
}
