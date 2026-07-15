package it.polito.eurotransit.payments.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

// created_at is left to the DB default (now()), so it is not mapped here.
@Table("processed_requests")
data class ProcessedRequestEntity(
    @Id @Column("idempotency_key") val idempotencyKey: String,
    @Column("transaction_id") val transactionId: String,
)
