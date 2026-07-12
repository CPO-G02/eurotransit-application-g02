package it.polito.eurotransit.payments.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers(
                        "/actuator/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/webjars/**",
                    ).permitAll()
                    .pathMatchers("/api/v1/payments/**").authenticated()
                    .anyExchange().permitAll()
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { }
            }
            .build()
    }

    @Bean
    fun jwtDecoder(
        @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") issuerUri: String,
        @Value("\${app.security.jwt.jwk-set-uri}") jwkSetUri: String,
        @Value("\${app.security.jwt.audience}") audience: String,
    ): ReactiveJwtDecoder {
        // Keys come from jwkSetUri (Keycloak's in-cluster Service) rather than
        // ReactiveJwtDecoders.fromIssuerLocation(issuerUri), which would fetch
        // the discovery document from the public issuer-uri at startup -
        // making boot depend on external ingress/DNS being reachable.
        // Issuer validation still checks against issuerUri unchanged, since
        // that's the value Keycloak actually stamps on real tokens.
        val decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                JwtAudienceValidator(audience),
            ),
        )
        return decoder
    }
}
