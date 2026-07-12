package it.polito.eurotransit.orders.client

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import it.polito.eurotransit.orders.dto.PaymentAuthorizeResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.core.publisher.Mono

@Component
class PaymentClient(
    webClientBuilder: WebClient.Builder,
    @Value("\${app.payments.url}") private val paymentsUrl: String,
    // Reuses the orders-service token already used for Inventory; gated by the
    // same app.security.service-token.* toggle.
    private val serviceTokenProvider: ServiceTokenProvider? = null,
) {
    private val webClient = webClientBuilder
        .baseUrl(paymentsUrl)
        .filter(bearerTokenFilter())
        .build()

    @CircuitBreaker(name = "payments-client", fallbackMethod = "fallbackPayment")
    suspend fun authorizePayment(req: PaymentAuthorizeRequest): PaymentAuthorizeResponse {
        return webClient.post()
            .uri("/authorize")
            .bodyValue(req)
            .retrieve()
            .awaitBody<PaymentAuthorizeResponse>()
    }

    suspend fun fallbackPayment(req: PaymentAuthorizeRequest, t: Throwable): PaymentAuthorizeResponse {
        return PaymentAuthorizeResponse(
            transaction_id = null,
            status = "DECLINED",
            reason = "payment_system_unavailable",
        )
    }

    private fun bearerTokenFilter(): ExchangeFilterFunction {
        return ExchangeFilterFunction.ofRequestProcessor { request ->
            serviceTokenProvider?.accessToken()
                ?.map { token ->
                    ClientRequest.from(request)
                        .headers { headers -> headers.setBearerAuth(token) }
                        .build()
                }
                ?.switchIfEmpty(Mono.just(request))
                ?: Mono.just(request)
        }
    }
}
