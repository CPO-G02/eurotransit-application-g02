package it.polito.eurotransit.catalog.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal


@Table("seat_classes")
data class SeatClassEntity(
    @Id val id: Long? = null,
    @Column("train_id") val trainId: String,
    @Column("seat_class") val seatClass: String,
    val price: BigDecimal,
    val currency: String,
    val available: Int,
)
