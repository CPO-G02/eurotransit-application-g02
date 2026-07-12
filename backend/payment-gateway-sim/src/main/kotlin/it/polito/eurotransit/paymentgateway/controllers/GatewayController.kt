package it.polito.eurotransit.paymentgateway.controllers

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import it.polito.eurotransit.paymentgateway.dto.ChargeRequest
import it.polito.eurotransit.paymentgateway.dto.ChargeResponse
import it.polito.eurotransit.paymentgateway.gateway.ChargeGateway
import it.polito.eurotransit.paymentgateway.gateway.LocalChargeGateway
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@Tag(
    name = "Payment Gateway (Stripe adapter)",
    description = "Real Stripe-backed adapter with a header-driven fault-injection short-circuit for chaos testing.",
)
@RestController
class GatewayController(
    @Qualifier("chargeGateway") private val normalGateway: ChargeGateway,
    @Qualifier("localChargeGateway") private val localGateway: LocalChargeGateway,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Operation(
        summary = "Charge",
        description = "Normal path calls Stripe. The X-Simulate-* headers let a chaos/test harness force " +
            "latency/failure and skip Stripe; Payments never sends them in normal operation.",
    )
    @PostMapping("/gateway/charge")
    suspend fun charge(
        @RequestBody request: ChargeRequest,
        @Parameter(description = "Delay the response by this many milliseconds (fault injection)")
        @RequestHeader(name = "X-Simulate-Delay-Ms", required = false) delayMs: Long?,
        @Parameter(description = "Respond 503 to simulate the gateway being down (fault injection)")
        @RequestHeader(name = "X-Simulate-Failure", required = false) failure: Boolean?,
    ): ResponseEntity<ChargeResponse> {
        if (delayMs != null || failure != null) {
            if (delayMs != null && delayMs > 0) {
                delay(delayMs)
            }
            if (failure == true) {
                logger.warn("event=gateway_simulated_failure order_id={}", request.orderId)
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
            }
            return ResponseEntity.ok(localGateway.charge(request))
        }

        return ResponseEntity.ok(normalGateway.charge(request))
    }
}
