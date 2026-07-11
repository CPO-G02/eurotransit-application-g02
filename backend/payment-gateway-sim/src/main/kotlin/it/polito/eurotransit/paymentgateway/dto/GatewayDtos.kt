package it.polito.eurotransit.paymentgateway.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

data class ChargeRequest(
    @JsonProperty("order_id") val orderId: String,
    val amount: BigDecimal,
    val currency: String,
)

data class ChargeResponse(
    val decision: String,
    val reason: String? = null,
)
