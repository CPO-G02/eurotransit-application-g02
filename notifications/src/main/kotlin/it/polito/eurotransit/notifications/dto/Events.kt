package it.polito.eurotransit.notifications.dto

// event for confirmed orders
data class OrderConfirmedEvent(
    val event_id: String,
    val order_id: String,
    val user_email: String,
    val train_id: String,
    val seat_class: String,
    val quantity: Int,
    val amount: Double,
    val transaction_id: String
)

// event for failed orders
data class OrderFailedEvent(
    val event_id: String,
    val order_id: String,
    val reservation_id: String?, // nullable if no reservation was made
    val reason: String,
    val user_email: String
)