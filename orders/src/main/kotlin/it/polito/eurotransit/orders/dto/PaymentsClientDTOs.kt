package it.polito.eurotransit.orders.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

data class PaymentAuthorizeRequest(
    @JsonProperty("idempotency_key") val idempotency_key: String,
    @JsonProperty("user_id") val user_id: String,
    val amount: BigDecimal,
    val currency: String
)

data class PaymentAuthorizeResponse(
    val transaction_id: String? = null,
    val status: String,
    val reason: String? = null
)