package it.polito.eurotransit.orders.client

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import it.polito.eurotransit.orders.dto.InventoryReserveResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.core.publisher.Mono

@Component
class InventoryClient(
    webClientBuilder: WebClient.Builder,
    @Value("\${app.inventory.url}") private val inventoryUrl: String,
    private val serviceTokenProvider: ServiceTokenProvider? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val webClient = webClientBuilder
        .baseUrl(inventoryUrl)
        .filter(bearerTokenFilter())
        .build()

    @CircuitBreaker(name = "inventory-client")
    suspend fun reserveSeats(request: InventoryReserveRequest): InventoryReserveResponse {
        logger.info("Requesting inventory reservation for order ${request.idempotency_key}")
        
        return try {
            webClient.post()
                .uri("/reserve")
                .bodyValue(request)
                .retrieve()
                .awaitBody<InventoryReserveResponse>()
        } catch (e: WebClientResponseException) {
            if (e.statusCode == HttpStatus.CONFLICT) {
                logger.warn("Reservation failed for order ${request.idempotency_key}: Insufficient seats")
                e.getResponseBodyAs(InventoryReserveResponse::class.java) 
                    ?: InventoryReserveResponse(status = "INSUFFICIENT_SEATS")
            } else {
                logger.error("Unexpected error calling inventory for order ${request.idempotency_key}: ${e.message}")
                throw e
            }
        }
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
