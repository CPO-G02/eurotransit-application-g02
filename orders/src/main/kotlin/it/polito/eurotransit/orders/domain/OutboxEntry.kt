package it.polito.eurotransit.orders.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("outbox")
data class OutboxEntry(
    @Id val id: Long? = null,
    val eventId: String,
    val topic: String,
    val payload: String, // stored as jsonb in postgres
    val createdAt: LocalDateTime? = null,
    val sentAt: LocalDateTime? = null
)