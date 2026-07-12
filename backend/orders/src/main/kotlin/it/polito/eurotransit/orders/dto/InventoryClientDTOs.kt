package it.polito.eurotransit.orders.dto

data class InventoryReserveRequest(
    val idempotency_key: String,
    val train_id: String,
    val seat_class: String,
    val quantity: Int
)

data class InventoryReserveResponse(
    val reservation_id: String? = null,
    val status: String
)