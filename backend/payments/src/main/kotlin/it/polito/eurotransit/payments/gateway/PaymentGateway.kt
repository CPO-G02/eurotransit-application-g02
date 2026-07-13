package it.polito.eurotransit.payments.gateway

import it.polito.eurotransit.payments.dto.AuthorizeRequest

// Decline reason (§1.5) for "no usable decision": breaker open, or the call
// failed/timed out. Never memoised — the charge outcome is unknown.
const val CIRCUIT_BREAKER_OPEN = "circuit_breaker_open"

sealed interface GatewayDecision {
    data object Authorized : GatewayDecision
    data class Declined(val reason: String) : GatewayDecision
}

interface PaymentGateway {
    suspend fun authorize(request: AuthorizeRequest): GatewayDecision
}
