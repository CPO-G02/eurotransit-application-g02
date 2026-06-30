package it.polito.eurotransit.orders.client

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.math.BigDecimal

// payment request dto
data class PaymentRequest(
    @JsonProperty("idempotency_key") val idempotencyKey: String,
    @JsonProperty("user_id") val userId: String,
    val amount: BigDecimal,
    val currency: String
)

// payment response dto
data class PaymentResponse(
    @JsonProperty("transaction_id") val transactionId: String?,
    val status: String,
    val reason: String?
)

@Component
class PaymentClient(
    webClientBuilder: WebClient.Builder,
    @Value("\${app.payments.url}") private val paymentsUrl: String
) {
    private val webClient = webClientBuilder.baseUrl(paymentsUrl).build()

    // authorize payment synchronously with native fallback
    fun authorizePayment(req: PaymentRequest): Mono<PaymentResponse> {
        return webClient.post()
            .uri("/authorize")
            .bodyValue(req)
            .retrieve()
            .bodyToMono(PaymentResponse::class.java)
            .onErrorResume { fallbackPayment(req, it) }
    }

    // fallback if payments service is down
    fun fallbackPayment(req: PaymentRequest, t: Throwable): Mono<PaymentResponse> {
        return Mono.just(
            PaymentResponse(
                transactionId = null,
                status = "DECLINED",
                reason = "payment_system_unavailable"
            )
        )
    }
}