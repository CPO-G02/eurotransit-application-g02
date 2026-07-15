package it.polito.eurotransit.inventory.repositories

import it.polito.eurotransit.inventory.entities.ProcessedEventEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ProcessedEventRepository : CoroutineCrudRepository<ProcessedEventEntity, String> {
    @Modifying
    @Query("INSERT INTO processed_events (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING")
    suspend fun insertIfAbsent(eventId: String): Int
}
