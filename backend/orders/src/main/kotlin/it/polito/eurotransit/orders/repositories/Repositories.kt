package it.polito.eurotransit.orders.repositories

import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.entities.ProcessedEvent
import it.polito.eurotransit.orders.entities.ProcessedRequest
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

// order repository
@Repository
interface OrderRepository : CoroutineCrudRepository<Order, String> {
    fun findByUserId(userId: String): Flow<Order>
}

// frontend deduplication repository
@Repository
interface ProcessedRequestRepository : CoroutineCrudRepository<ProcessedRequest, String>

// kafka deduplication repository
@Repository
interface ProcessedEventRepository : CoroutineCrudRepository<ProcessedEvent, String>