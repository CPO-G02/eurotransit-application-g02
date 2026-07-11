package it.polito.eurotransit.catalog.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant


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
    @JsonProperty("class") val seatClass: String,
    val price: BigDecimal,
    val currency: String,
    val available: Int,
)

data class ErrorResponse(
    val error: String,
)
