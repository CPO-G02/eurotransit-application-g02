package it.polito.eurotransit.orders.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.LocalDateTime

// main order entity
@Table("orders")
data class Order(
    @Id
    val orderId: String,
    val userId: String,
    val userEmail: String,
    val trainId: String,
    val seatClass: String,
    val quantity: Int,
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val transactionId: String? = null,
    val createdAt: LocalDateTime? = LocalDateTime.now(),
    val confirmedAt: LocalDateTime? = null
)

// frontend deduplication
@Table("processed_requests")
data class ProcessedRequest(
    @Id
    val idempotencyKey: String,
    val orderId: String,
    val createdAt: LocalDateTime? = LocalDateTime.now()
)

// kafka deduplication
@Table("processed_events")
data class ProcessedEvent(
    @Id
    val eventId: String,
    val result: String? = null,
    val createdAt: LocalDateTime? = LocalDateTime.now()
)