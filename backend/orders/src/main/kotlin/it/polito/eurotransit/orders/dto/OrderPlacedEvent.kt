package it.polito.eurotransit.orders.dto

data class OrderPlacedEvent(
    val eventId: String,
    val orderId: String,
    val trainId: String,
    val seatClass: String,
    val quantity: Int
)