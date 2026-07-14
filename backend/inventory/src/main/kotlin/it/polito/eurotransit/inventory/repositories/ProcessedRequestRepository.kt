package it.polito.eurotransit.inventory.repositories

import it.polito.eurotransit.inventory.entities.ProcessedRequestEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ProcessedRequestRepository : CoroutineCrudRepository<ProcessedRequestEntity, String> {
    @Modifying
    @Query(
        """
        INSERT INTO processed_requests (idempotency_key, reservation_id)
        VALUES (:idempotencyKey, :reservationId)
        ON CONFLICT DO NOTHING
        """,
    )
    suspend fun insertIfAbsent(idempotencyKey: String, reservationId: String): Int
}
