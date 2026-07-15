package it.polito.eurotransit.orders.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class OrderPlacedEvent(
    @JsonProperty("event_id")
    val eventId: String,
    @JsonProperty("event_timestamp")
    val eventTimestamp: String,
    @JsonProperty("order_id")
    val orderId: String,
    @JsonProperty("train_id")
    val trainId: String,
    @JsonProperty("seat_class")
    val seatClass: String,
    val quantity: Int
)
