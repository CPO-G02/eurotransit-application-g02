package it.polito.eurotransit.payments.gateway

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import it.polito.eurotransit.payments.dto.AuthorizeRequest
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.math.BigDecimal
import java.time.Duration

@Component
class HttpPaymentGateway(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    @Value("\${app.gateway.url}") gatewayUrl: String,
    @Value("\${app.gateway.timeout:5s}") timeout: Duration,
) : PaymentGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    // responseTimeout bounds a truly hung call as a failure; it is set above the
    // slow-call threshold so merely-slow calls still register as slow (not error).
    private val webClient = WebClient.builder()
        .baseUrl(gatewayUrl)
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
        .build()

    // Instance configured from resilience4j.circuitbreaker in application.yaml.
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("payment-gateway")

    override suspend fun authorize(request: AuthorizeRequest): GatewayDecision =
        try {
            circuitBreaker.executeSuspendFunction { callGateway(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fires when the breaker is open (CallNotPermittedException) and when
            // the call itself fails/times out: either way there is no usable
            // decision, so return the contract's circuit_breaker_open. Orders'
            // bounded retry absorbs transient blips.
            log.warn("event=gateway_unavailable order_id={} error={}", request.idempotencyKey, e.toString())
            GatewayDecision.Declined(CIRCUIT_BREAKER_OPEN)
        }

    private suspend fun callGateway(request: AuthorizeRequest): GatewayDecision {
        val response = webClient.post()
            .uri("/gateway/charge")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(GatewayChargeRequest(request.idempotencyKey, request.amount, request.currency))
            .retrieve()
            .awaitBody<GatewayChargeResponse>()

        return if (response.decision == "DECLINED") {
            GatewayDecision.Declined(response.reason ?: "insufficient_funds")
        } else {
            GatewayDecision.Authorized
        }
    }
}

private data class GatewayChargeRequest(
    @JsonProperty("order_id") val orderId: String,
    val amount: BigDecimal,
    val currency: String,
)

private data class GatewayChargeResponse(
    val decision: String = "",
    val reason: String? = null,
)
