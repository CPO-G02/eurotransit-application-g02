package it.polito.eurotransit.inventory.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ReserveRequest(
    @JsonProperty("idempotency_key") val idempotencyKey: String,
    @JsonProperty("train_id") val trainId: String,
    @JsonProperty("seat_class") val seatClass: String,
    val quantity: Int,
)

data class ReserveResponse(
    @JsonProperty("reservation_id") val reservationId: String,
    val status: String,
)

data class InsufficientSeatsResponse(
    val status: String,
)

// reservation_id is null when the failure happened before any reservation
// (Stage 1 no-seats path); everything else is accepted and ignored.
data class OrderFailedEvent(
    @JsonProperty("event_id") val eventId: String? = null,
    @JsonProperty("order_id") val orderId: String? = null,
    @JsonProperty("reservation_id") val reservationId: String? = null,
    val reason: String? = null,
    @JsonProperty("user_email") val userEmail: String? = null,
)
