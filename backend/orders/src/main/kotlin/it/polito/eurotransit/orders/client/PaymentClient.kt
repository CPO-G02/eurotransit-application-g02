package it.polito.eurotransit.orders.client

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import it.polito.eurotransit.orders.dto.PaymentAuthorizeRequest
import it.polito.eurotransit.orders.dto.PaymentAuthorizeResponse
import io.netty.channel.ChannelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Component
class PaymentClient(
    webClientBuilder: WebClient.Builder,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    @Value("\${app.payments.url}") private val paymentsUrl: String,
    @Value("\${resilience4j.timelimiter.instances.payments-client.timeout-duration:2s}")
    private val paymentsTimeout: Duration = Duration.ofSeconds(2),
    private val circuitBreakerRegistry: CircuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults(),
    @Value("\${app.payments.timeout:6s}") timeout: Duration,
    // Reuses the orders-service token already used for Inventory; gated by the
    // same app.security.service-token.* toggle.
    private val serviceTokenProvider: ServiceTokenProvider? = null,
) {
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("payments-client")
    private val webClient = webClientBuilder.clone()
        .baseUrl(paymentsUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, paymentsTimeout.toMillis().toInt())
                    .responseTimeout(paymentsTimeout),
            ),
        )
        .filter(bearerTokenFilter())
        .build()

    suspend fun authorizePayment(req: PaymentAuthorizeRequest): PaymentAuthorizeResponse {
        return try {
            circuitBreaker.executeSuspendFunction { doAuthorizePayment(req) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fallbackPayment(req, t)
        }
    }

    private suspend fun doAuthorizePayment(req: PaymentAuthorizeRequest): PaymentAuthorizeResponse {
        return webClient.post()
            .uri("/authorize")
            .bodyValue(req)
            .exchangeToMono { response ->
                when {
                    response.statusCode().is2xxSuccessful -> response.bodyToMono(PaymentAuthorizeResponse::class.java)
                    response.statusCode() == HttpStatus.PAYMENT_REQUIRED -> response.bodyToMono(PaymentAuthorizeResponse::class.java)
                    else -> response.createException().flatMap { Mono.error(it) }
                }
            }
            .awaitSingle()
    }
    private val logger = LoggerFactory.getLogger(javaClass)

    // Budget exceeds Payments' own 5s gateway timeout so Orders doesn't abort a
    // call Payments is still legitimately working on. responseTimeout makes a
    // hung Payments a recorded breaker failure. Mirrors HttpPaymentGateway.
    private val webClient = webClientBuilder
        .baseUrl(paymentsUrl)
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
        .filter(bearerTokenFilter())
        .build()

    // Programmatic, not @CircuitBreaker: the annotation aspect is a no-op on
    // suspend functions. See docs/ai-logs.md.
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("payments-client")

    suspend fun authorizePayment(req: PaymentAuthorizeRequest): PaymentAuthorizeResponse =
        try {
            circuitBreaker.executeSuspendFunction {
                try {
                    webClient.post()
                        .uri("/authorize")
                        .bodyValue(req)
                        .retrieve()
                        .awaitBody<PaymentAuthorizeResponse>()
                } catch (e: WebClientResponseException) {
                    if (e.statusCode == HttpStatus.PAYMENT_REQUIRED) {
                        // 402 = a genuine card decline (contract §1.5): a valid
                        // business answer, not an outage. Propagate its real reason
                        // and count it as a successful call, so a burst of declines
                        // can never trip the breaker.
                        e.getResponseBodyAs(PaymentAuthorizeResponse::class.java)
                            ?: PaymentAuthorizeResponse(status = "DECLINED", reason = "insufficient_funds")
                    } else {
                        throw e
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Breaker open (CallNotPermittedException) or the call failed/timed
            // out: no decision from Payments, so fail safe as DECLINED.
            logger.warn("event=payments_unavailable order_id=${req.idempotency_key} error=$e")
            fallbackPayment(req, e)
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
