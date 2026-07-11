package it.polito.eurotransit.orders.client

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Instant

data class ServiceTokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("expires_in") val expiresIn: Long = 60
)

@Component
class ServiceTokenProvider(
    @Value("\${app.security.service-token.inventory.enabled:false}") private val enabled: Boolean,
    @Value("\${app.security.service-token.inventory.token-uri:}") private val tokenUri: String,
    @Value("\${app.security.service-token.inventory.client-id:orders-service}") private val clientId: String,
    @Value("\${app.security.service-token.inventory.client-secret:}") private val clientSecret: String,
    @Value("\${app.security.service-token.inventory.scope:}") private val scope: String
) {
    private val webClient = WebClient.builder().build()

    @Volatile
    private var cachedToken: CachedServiceToken? = null

    fun accessToken(): Mono<String> {
        if (!enabled) {
            return Mono.empty()
        }

        val token = cachedToken
        if (token != null && token.expiresAt.isAfter(Instant.now().plusSeconds(30))) {
            return Mono.just(token.value)
        }

        require(tokenUri.isNotBlank()) {
            "app.security.service-token.inventory.token-uri must be configured when service tokens are enabled"
        }
        require(clientSecret.isNotBlank()) {
            "app.security.service-token.inventory.client-secret must be configured when service tokens are enabled"
        }

        var form = BodyInserters
            .fromFormData("grant_type", "client_credentials")
            .with("client_id", clientId)
            .with("client_secret", clientSecret)

        if (scope.isNotBlank()) {
            form = form.with("scope", scope)
        }

        return webClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .bodyToMono(ServiceTokenResponse::class.java)
            .map { response ->
                cachedToken = CachedServiceToken(
                    value = response.accessToken,
                    expiresAt = Instant.now().plusSeconds(response.expiresIn)
                )
                response.accessToken
            }
    }

    private data class CachedServiceToken(
        val value: String,
        val expiresAt: Instant
    )
}
