package it.polito.eurotransit.orders.repository

import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.domain.ProcessedEvent
import it.polito.eurotransit.orders.domain.ProcessedRequest
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository

// order repository
@Repository
interface OrderRepository : ReactiveCrudRepository<Order, String>

// frontend deduplication repository
@Repository
interface ProcessedRequestRepository : ReactiveCrudRepository<ProcessedRequest, String>

// kafka deduplication repository
@Repository
interface ProcessedEventRepository : ReactiveCrudRepository<ProcessedEvent, String>