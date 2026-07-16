package it.polito.eurotransit.orders.repositories

import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.entities.ProcessedEvent
import it.polito.eurotransit.orders.entities.ProcessedRequest
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime

// order repository
@Repository
interface OrderRepository : CoroutineCrudRepository<Order, String> {
    @Modifying
    @Query(
        """
        INSERT INTO orders (
            order_id, user_id, user_email, train_id, seat_class, quantity,
            amount, currency, status, transaction_id, created_at, confirmed_at
        )
        VALUES (
            :orderId, :userId, :userEmail, :trainId, :seatClass, :quantity,
            :amount, :currency, :status, :transactionId, :createdAt, :confirmedAt
        )
        """,
    )
    suspend fun insertNew(
        orderId: String,
        userId: String,
        userEmail: String,
        trainId: String,
        seatClass: String,
        quantity: Int,
        amount: BigDecimal,
        currency: String,
        status: String,
        transactionId: String?,
        createdAt: LocalDateTime?,
        confirmedAt: LocalDateTime?,
    ): Int

    fun findByUserId(userId: String): Flow<Order>

    // Backs the contract's §4.3 pipeline-completion SLI: orders stuck in
    // PENDING longer than the 30s budget without reaching a terminal state.
    suspend fun countByStatusAndCreatedAtBefore(status: String, cutoff: LocalDateTime): Long
}

// frontend deduplication repository
@Repository
interface ProcessedRequestRepository : CoroutineCrudRepository<ProcessedRequest, String> {
    @Modifying
    @Query(
        """
        INSERT INTO processed_requests (idempotency_key, order_id)
        VALUES (:idempotencyKey, :orderId)
        ON CONFLICT DO NOTHING
        """,
    )
    suspend fun insertIfAbsent(idempotencyKey: String, orderId: String): Int
}

// kafka deduplication repository
@Repository
interface ProcessedEventRepository : CoroutineCrudRepository<ProcessedEvent, String> {
    @Modifying
    @Query("INSERT INTO processed_events (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING")
    suspend fun insertIfAbsent(eventId: String): Int
}
