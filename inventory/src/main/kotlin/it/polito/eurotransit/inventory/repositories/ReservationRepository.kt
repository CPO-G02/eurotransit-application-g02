package it.polito.eurotransit.inventory.repositories

import it.polito.eurotransit.inventory.entities.ReservationEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ReservationRepository : CoroutineCrudRepository<ReservationEntity, Long> {

    suspend fun findByReservationId(reservationId: String): ReservationEntity?

    // Returns 1 the first time, 0 on redelivery, so seats are never released twice.
    @Modifying
    @Query("UPDATE reservations SET status = 'RELEASED' WHERE reservation_id = :reservationId AND status = 'RESERVED'")
    suspend fun markReleased(reservationId: String): Int
}
