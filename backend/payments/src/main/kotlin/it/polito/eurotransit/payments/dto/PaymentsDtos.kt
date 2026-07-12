package it.polito.eurotransit.payments.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

data class AuthorizeRequest(
    @JsonProperty("idempotency_key") val idempotencyKey: String,
    @JsonProperty("user_id") val userId: String,
    val amount: BigDecimal,
    val currency: String,
)

data class AuthorizeResponse(
    @JsonProperty("transaction_id") val transactionId: String,
    val status: String,
)

data class DeclinedResponse(
    val status: String,
    val reason: String,
)
