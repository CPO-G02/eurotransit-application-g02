package it.polito.eurotransit.orders.client

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Instant

data class ServiceTokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("expires_in") val expiresIn: Long = 60
)

@Component
class ServiceTokenProvider(
    @Value("\${app.security.service-token.enabled:false}") private val enabled: Boolean,
    @Value("\${app.security.service-token.token-uri:}") private val tokenUri: String,
    @Value("\${app.security.service-token.client-id:orders-service}") private val clientId: String,
    @Value("\${app.security.service-token.client-secret:}") private val clientSecret: String,
    @Value("\${app.security.service-token.scope:}") private val scope: String,
    @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") issuerUri: String,
    webClientBuilder: WebClient.Builder = WebClient.builder()
) {
    private val webClient = webClientBuilder.build()
    @Volatile
    private var cachedToken: CachedServiceToken? = null

    // tokenUri points at the in-cluster Keycloak Service (see its own comment
    // below) with no proxy in front, so Keycloak sees a plain http request and
    // stamps iss: http://<pod-host>:8080/... instead of the public issuer -
    // every resource server then rejects the token as an invalid issuer.
    // Keycloak trusts X-Forwarded-* unconditionally (proxy: xforwarded), so
    // sending the public issuer's own scheme/host/port here makes it stamp
    // the correct public iss even for this direct in-cluster call. Confirmed
    // live 2026-07-16 by decoding a token minted with vs. without these headers.
    private val forwardedUri = URI(issuerUri)
    private val forwardedProto = forwardedUri.scheme
    private val forwardedHost = forwardedUri.host
    private val forwardedPort = if (forwardedUri.port != -1) forwardedUri.port else if (forwardedProto == "https") 443 else 80

    fun accessToken(): Mono<String> {
        if (!enabled) {
            return Mono.empty()
        }

        val token = cachedToken
        if (token != null && token.expiresAt.isAfter(Instant.now().plusSeconds(30))) {
            return Mono.just(token.value)
        }

        require(tokenUri.isNotBlank()) {
            "app.security.service-token.token-uri must be configured when service tokens are enabled"
        }
        require(clientSecret.isNotBlank()) {
            "app.security.service-token.client-secret must be configured when service tokens are enabled"
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
            .header("X-Forwarded-Proto", forwardedProto)
            .header("X-Forwarded-Host", forwardedHost)
            .header("X-Forwarded-Port", forwardedPort.toString())
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
