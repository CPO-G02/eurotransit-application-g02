package it.polito.eurotransit.paymentgateway.controllers

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import it.polito.eurotransit.paymentgateway.dto.ChargeRequest
import it.polito.eurotransit.paymentgateway.dto.ChargeResponse
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@Tag(name = "Payment Gateway (simulated)", description = "Stand-in for the external payment processor")
@RestController
class GatewayController(
    @Value("\${app.gateway.decline-above}") private val declineAbove: BigDecimal,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Operation(
        summary = "Charge",
        description = "Authorizes or declines a charge. The X-Simulate-* headers let a test or chaos " +
            "harness force latency/failure deterministically; Payments never sends them in normal operation.",
    )
    @PostMapping("/gateway/charge")
    suspend fun charge(
        @RequestBody request: ChargeRequest,
        @Parameter(description = "Delay the response by this many milliseconds (fault injection)")
        @RequestHeader(name = "X-Simulate-Delay-Ms", required = false) delayMs: Long?,
        @Parameter(description = "Respond 503 to simulate the gateway being down (fault injection)")
        @RequestHeader(name = "X-Simulate-Failure", required = false) failure: Boolean?,
    ): ResponseEntity<ChargeResponse> {
        if (delayMs != null && delayMs > 0) {
            delay(delayMs)
        }
        if (failure == true) {
            logger.warn("event=gateway_simulated_failure order_id={}", request.orderId)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }

        return if (request.amount > declineAbove) {
            logger.info("event=charge_declined order_id={} amount={}", request.orderId, request.amount)
            ResponseEntity.ok(ChargeResponse(decision = "DECLINED", reason = "insufficient_funds"))
        } else {
            logger.info("event=charge_authorized order_id={} amount={}", request.orderId, request.amount)
            ResponseEntity.ok(ChargeResponse(decision = "AUTHORIZED"))
        }
    }
}
