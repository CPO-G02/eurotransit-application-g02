package it.polito.eurotransit.orders.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDateTime

// post /api/v1/orders request
data class OrderRequest(
    @JsonProperty("idempotency_key") val idempotencyKey: String,
    @JsonProperty("user_id") val userId: String,
    @JsonProperty("user_email") val userEmail: String,
    @JsonProperty("train_id") val trainId: String,
    @JsonProperty("seat_class") val seatClass: String,
    val quantity: Int,
    val amount: BigDecimal,
    val currency: String
)

// post /api/v1/orders 202 response
data class OrderResponse(
    @JsonProperty("order_id") val orderId: String,
    val status: String
)

// post /api/v1/orders 409 conflict response
data class OrderConflictResponse(
    @JsonProperty("order_id") val orderId: String,
    val status: String,
    val message: String
)

// get /api/v1/orders/{id} response
data class OrderStatusResponse(
    @JsonProperty("order_id") val orderId: String,
    val status: String,
    @JsonProperty("train_id") val trainId: String,
    @JsonProperty("seat_class") val seatClass: String,
    val quantity: Int,
    val amount: BigDecimal,
    val currency: String,
    @JsonProperty("transaction_id") val transactionId: String?,
    @JsonProperty("created_at") val createdAt: LocalDateTime?,
    @JsonProperty("confirmed_at") val confirmedAt: LocalDateTime?
)