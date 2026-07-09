package it.polito.eurotransit.orders.repository

import it.polito.eurotransit.orders.domain.OutboxEntry
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