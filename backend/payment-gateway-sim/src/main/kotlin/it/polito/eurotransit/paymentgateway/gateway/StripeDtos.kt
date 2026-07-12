package it.polito.eurotransit.paymentgateway.gateway

import com.fasterxml.jackson.annotation.JsonProperty

data class StripePaymentIntent(
    val status: String? = null,
    @JsonProperty("last_payment_error") val lastPaymentError: StripeError? = null,
)

data class StripeErrorEnvelope(
    val error: StripeError? = null,
)

data class StripeError(
    val code: String? = null,
    @JsonProperty("decline_code") val declineCode: String? = null,
    val message: String? = null,
)

class StripeGatewayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
