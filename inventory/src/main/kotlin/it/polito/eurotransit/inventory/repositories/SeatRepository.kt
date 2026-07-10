package it.polito.eurotransit.inventory.repositories

import it.polito.eurotransit.inventory.entities.SeatEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SeatRepository : CoroutineCrudRepository<SeatEntity, Long> {

    // Guard and decrement in one atomic UPDATE so concurrent requests can never
    // oversell. Returns 1 when reserved, 0 when insufficient seats.
    @Modifying
    @Query(
        """
        UPDATE seats
        SET available = available - :quantity
        WHERE train_id = :trainId
          AND seat_class = :seatClass
          AND available >= :quantity
        """,
    )
    suspend fun reserve(trainId: String, seatClass: String, quantity: Int): Int

    @Modifying
    @Query(
        """
        UPDATE seats
        SET available = available + :quantity
        WHERE train_id = :trainId
          AND seat_class = :seatClass
        """,
    )
    suspend fun release(trainId: String, seatClass: String, quantity: Int): Int
}
