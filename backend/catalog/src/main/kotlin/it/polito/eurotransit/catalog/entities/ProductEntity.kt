package it.polito.eurotransit.catalog.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant


@Table("products")
data class ProductEntity(
    @Id @Column("train_id") val trainId: String,
    val origin: String,
    val destination: String,
    val departure: Instant,
)
