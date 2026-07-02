package it.polito.eurotransit.orders.client

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
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

    // authorize payment synchronously with circuit breaker and coroutines
    @CircuitBreaker(name = "payments-client", fallbackMethod = "fallbackPayment")
    suspend fun authorizePayment(req: PaymentRequest): PaymentResponse {
        return webClient.post()
            .uri("/authorize")
            .bodyValue(req)
            .retrieve()
            .awaitBody<PaymentResponse>()
    }

    // fallback if payments service is down
    suspend fun fallbackPayment(req: PaymentRequest, t: Throwable): PaymentResponse {
        return PaymentResponse(
            transactionId = null,
            status = "DECLINED",
            reason = "payment_system_unavailable"
        )
    }
}