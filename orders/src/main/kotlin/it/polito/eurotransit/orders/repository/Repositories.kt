package it.polito.eurotransit.orders.repository

import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.domain.ProcessedEvent
import it.polito.eurotransit.orders.domain.ProcessedRequest
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

// order repository
@Repository
interface OrderRepository : CoroutineCrudRepository<Order, String>

// frontend deduplication repository
@Repository
interface ProcessedRequestRepository : CoroutineCrudRepository<ProcessedRequest, String>

// kafka deduplication repository
@Repository
interface ProcessedEventRepository : CoroutineCrudRepository<ProcessedEvent, String>