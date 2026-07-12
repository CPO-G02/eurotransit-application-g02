package it.polito.eurotransit.orders.repositories

import it.polito.eurotransit.orders.entities.OutboxEntry
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface OutboxRepository : CoroutineCrudRepository<OutboxEntry, Long> {

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