package it.polito.eurotransit.payments.gateway

import it.polito.eurotransit.payments.dto.AuthorizeRequest

sealed interface GatewayDecision {
    data object Authorized : GatewayDecision
    data class Declined(val reason: String) : GatewayDecision
}

interface PaymentGateway {
    suspend fun authorize(request: AuthorizeRequest): GatewayDecision
}
