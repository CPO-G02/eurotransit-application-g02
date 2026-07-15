package it.polito.eurotransit.inventory.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table


@Table("processed_events")
data class ProcessedEventEntity(
    @Id @Column("event_id") val eventId: String,
)
