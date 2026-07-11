package it.polito.eurotransit.inventory.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("seats")
data class SeatEntity(
    @Id val id: Long? = null,
    @Column("train_id") val trainId: String,
    @Column("seat_class") val seatClass: String,
    val available: Int,
)
