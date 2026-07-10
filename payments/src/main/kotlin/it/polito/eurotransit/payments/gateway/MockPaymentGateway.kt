package it.polito.eurotransit.payments.gateway

import it.polito.eurotransit.payments.dto.AuthorizeRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

// Simulated gateway (human-approved rule): declines amounts above a
// configurable threshold so the 402 branch is demonstrable end-to-end.
// The real gateway call and its circuit breaker are a later task.

// TODO: add a circuit breaker to the real gateway call, and a fallback to 
// this mock gateway in case of failure
@Component
class MockPaymentGateway(
    @Value("\${app.gateway.decline-above}") private val declineAbove: BigDecimal,
) : PaymentGateway {

    override suspend fun authorize(request: AuthorizeRequest): GatewayDecision =
        if (request.amount > declineAbove) {
            GatewayDecision.Declined("insufficient_funds")
        } else {
            GatewayDecision.Authorized
        }
}
