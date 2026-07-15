package it.polito.eurotransit.payments.repositories

import it.polito.eurotransit.payments.entities.ProcessedRequestEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ProcessedRequestRepository : CoroutineCrudRepository<ProcessedRequestEntity, String> {

    // 1 when this caller wins the key, 0 on a duplicate. Not save(): a non-null
    // String @Id makes Spring Data issue an UPDATE. ON CONFLICT blocks on the
    // winner's row lock, so 0 means the winner has committed and is readable.
    @Modifying
    @Query(
        """
        INSERT INTO processed_requests (idempotency_key, transaction_id)
        VALUES (:idempotencyKey, :transactionId)
        ON CONFLICT DO NOTHING
        """,
    )
    suspend fun insertIfAbsent(idempotencyKey: String, transactionId: String): Int
}
