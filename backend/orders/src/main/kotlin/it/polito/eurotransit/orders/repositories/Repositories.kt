package it.polito.eurotransit.orders.repositories

import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.entities.ProcessedEvent
import it.polito.eurotransit.orders.entities.ProcessedRequest
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

// order repository
@Repository
interface OrderRepository : CoroutineCrudRepository<Order, String> {
    fun findByUserId(userId: String): Flow<Order>

    // Backs the contract's §4.3 pipeline-completion SLI: orders stuck in
    // PENDING longer than the 30s budget without reaching a terminal state.
    suspend fun countByStatusAndCreatedAtBefore(status: String, cutoff: LocalDateTime): Long
}

// frontend deduplication repository
@Repository
interface ProcessedRequestRepository : CoroutineCrudRepository<ProcessedRequest, String>

// kafka deduplication repository
@Repository
interface ProcessedEventRepository : CoroutineCrudRepository<ProcessedEvent, String>