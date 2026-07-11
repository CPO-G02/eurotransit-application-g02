package it.polito.eurotransit.inventory.service

import it.polito.eurotransit.inventory.dto.OrderFailedEvent
import it.polito.eurotransit.inventory.dto.ReserveRequest
import it.polito.eurotransit.inventory.dto.ReserveResponse
import it.polito.eurotransit.inventory.entities.ReservationEntity
import it.polito.eurotransit.inventory.exceptions.InsufficientSeatsException
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
) : InventoryService {

    private val log = LoggerFactory.getLogger(javaClass)

    // Request-level idempotency (processed_requests dedup) is out of scope here:
    // a duplicated reserve currently reserves again. See inventory/CLAUDE.md.
    @Transactional
    override suspend fun reserve(request: ReserveRequest): ReserveResponse {
        val reserved = seatRepository.reserve(request.trainId, request.seatClass, request.quantity)
        if (reserved == 0) {
            throw InsufficientSeatsException(request.trainId, request.seatClass, request.quantity)
        }

        val reservationId = "res-${UUID.randomUUID()}"
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

    @Transactional
    override suspend fun compensate(event: OrderFailedEvent) {
        val reservationId = event.reservationId ?: return

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
