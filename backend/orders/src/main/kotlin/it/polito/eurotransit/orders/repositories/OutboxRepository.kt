package it.polito.eurotransit.orders.repositories

import it.polito.eurotransit.orders.entities.OutboxEntry
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface OutboxRepository : CoroutineCrudRepository<OutboxEntry, Long> {

    @Modifying
    @Query(
        """
        INSERT INTO outbox (event_id, topic, payload)
        VALUES (:eventId, :topic, CAST(:payload AS jsonb))
        """,
    )
    suspend fun insert(eventId: String, topic: String, payload: String): Int

    // Only touches sent_at - the generic save() OutboxProcessor used to call
    // here re-writes every column including payload, which Spring Data R2DBC
    // binds as varchar against a jsonb column and fails with "column payload
    // is of type jsonb but expression is of type character varying". Since
    // that row then never gets marked sent, findPendingMessages returns it
    // first (ORDER BY id ASC) every cycle and the processor loop throws
    // before advancing - head-of-line blocking every message behind it.
    // Confirmed live 2026-07-16.
    @Modifying
    @Query("UPDATE outbox SET sent_at = :sentAt WHERE id = :id")
    suspend fun markSent(id: Long, sentAt: java.time.LocalDateTime): Int

    // fetch pending messages and lock rows to prevent double publishing
    @Query("""
        SELECT * FROM outbox 
        WHERE sent_at IS NULL 
        ORDER BY id ASC 
        FOR UPDATE SKIP LOCKED 
        LIMIT :limit
    """)
    suspend fun findPendingMessages(limit: Int): List<OutboxEntry>
}
