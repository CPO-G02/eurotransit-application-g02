package it.polito.eurotransit.catalog.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant

/**
 * Response DTOs for the public Catalog API. Kept separate from the persistence
 * entities so the wire contract (snake_case keys from the API contract §1.1) can
 * evolve independently of the database schema.
 *
 * Snake_case keys are pinned with @JsonProperty rather than a global naming
 * strategy so the mapping is explicit and survives standalone (de)serialization
 * in tests. Single-word fields need no annotation.
 */
data class ProductsResponse(
    val products: List<ProductResponse>,
)

data class ProductResponse(
    @JsonProperty("train_id") val trainId: String,
    val origin: String,
    val destination: String,
    val departure: Instant,
    @JsonProperty("seat_classes") val seatClasses: List<SeatClassDto>,
)

data class SeatClassDto(
    // "class" is a Kotlin reserved word, so the property is named seatClass and
    // pinned to the contract's "class" key explicitly.
    @JsonProperty("class") val seatClass: String,
    val price: BigDecimal,
    val currency: String,
    val available: Int,
)

data class ErrorResponse(
    val error: String,
)
