package it.polito.eurotransit.inventory.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

// created_at is left to the DB default (now()), so it is not mapped here.
@Table("reservations")
data class ReservationEntity(
    @Id val id: Long? = null,
    @Column("reservation_id") val reservationId: String,
    @Column("order_id") val orderId: String,
    @Column("train_id") val trainId: String,
    @Column("seat_class") val seatClass: String,
    val quantity: Int,
    val status: String,
)
