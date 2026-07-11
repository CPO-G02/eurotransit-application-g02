package it.polito.eurotransit.paymentgateway.gateway

import it.polito.eurotransit.paymentgateway.dto.ChargeRequest
import it.polito.eurotransit.paymentgateway.dto.ChargeResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Component("chargeGateway")
@ConditionalOnProperty(name = ["app.stripe.enabled"], havingValue = "true", matchIfMissing = true)
class StripeChargeGateway(
    @Value("\${app.stripe.base-url}") baseUrl: String,
    @Value("\${app.stripe.secret-key:}") private val secretKey: String,
    @Value("\${app.stripe.api-version}") private val apiVersion: String,
    @Value("\${app.stripe.payment-method}") private val paymentMethod: String,
    @Value("\${app.stripe.timeout-ms:5000}") timeoutMs: Long,
) : ChargeGateway {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(baseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMillis(timeoutMs)),
            ),
        )
        .build()

    init {
        check(secretKey.isNotBlank()) {
            "app.stripe.secret-key is blank while app.stripe.enabled=true; " +
                "provide STRIPE_SECRET_KEY or set app.stripe.enabled=false"
        }
    }

    override suspend fun charge(request: ChargeRequest): ChargeResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("amount", request.amount.movePointRight(2).toBigInteger().toString()) // minor units
            add("currency", request.currency.lowercase())
            add("payment_method", paymentMethod)
            add("confirm", "true")
            add("automatic_payment_methods[enabled]", "true")
            add("automatic_payment_methods[allow_redirects]", "never")
        }

        return webClient.post()
            .uri("/v1/payment_intents")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $secretKey")
            .header("Idempotency-Key", request.orderId) // dedupe Orders' bounded retries
            .header("Stripe-Version", apiVersion)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .awaitExchange { response ->
                val status = response.statusCode()
                when {
                    status.is2xxSuccessful -> mapIntent(response.awaitBody(), request)
                    status.value() == 402 -> mapDecline(response.awaitBody(), request)
                    else -> {
                        val body = runCatching { response.awaitBody<String>() }.getOrDefault("<no body>")
                        throw StripeGatewayException("Stripe returned $status: $body")
                    }
                }
            }
    }

    private fun mapIntent(intent: StripePaymentIntent, request: ChargeRequest): ChargeResponse =
        if (intent.status == "succeeded") {
            logger.info("event=charge_authorized_stripe order_id={}", request.orderId)
            ChargeResponse(decision = "AUTHORIZED")
        } else {
            val reason = intent.lastPaymentError?.declineCode
                ?: intent.lastPaymentError?.code
                ?: intent.status
                ?: "card_declined"
            logger.info(
                "event=charge_declined_stripe order_id={} status={} reason={}",
                request.orderId, intent.status, reason,
            )
            ChargeResponse(decision = "DECLINED", reason = reason)
        }

    private fun mapDecline(envelope: StripeErrorEnvelope, request: ChargeRequest): ChargeResponse {
        val reason = envelope.error?.declineCode ?: envelope.error?.code ?: "card_declined"
        logger.info("event=charge_declined_stripe order_id={} reason={}", request.orderId, reason)
        return ChargeResponse(decision = "DECLINED", reason = reason)
    }
}
