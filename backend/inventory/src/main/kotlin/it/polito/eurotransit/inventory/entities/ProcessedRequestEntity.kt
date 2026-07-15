package it.polito.eurotransit.inventory.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

// created_at is left to the DB default (now()), so it is not mapped here.
@Table("processed_requests")
data class ProcessedRequestEntity(
    @Id @Column("idempotency_key") val idempotencyKey: String,
    @Column("reservation_id") val reservationId: String,
)
