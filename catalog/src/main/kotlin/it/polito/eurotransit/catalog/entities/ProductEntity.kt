package it.polito.eurotransit.catalog.entities

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * A train offering. `train_id` is a natural, externally-assigned primary key
 * (e.g. "TR-101"), so rows are only ever inserted via the seed script, never
 * saved through the repository — this side-steps R2DBC's "provided id looks
 * non-new" insert/update ambiguity.
 */
@Table("products")
data class ProductEntity(
    @Id @Column("train_id") val trainId: String,
    val origin: String,
    val destination: String,
    val departure: Instant,
)
