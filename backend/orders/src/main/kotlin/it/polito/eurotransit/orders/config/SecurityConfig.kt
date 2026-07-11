package it.polito.eurotransit.orders.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders
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
                        "/webjars/**"
                    ).permitAll()
                    .pathMatchers("/api/v1/orders/**").authenticated()
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
        @Value("\${app.security.jwt.audience}") audience: String
    ): ReactiveJwtDecoder {
        val decoder = ReactiveJwtDecoders.fromIssuerLocation(issuerUri) as NimbusReactiveJwtDecoder
        val validator = DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(issuerUri),
            JwtAudienceValidator(audience)
        )
        decoder.setJwtValidator(validator)
        return decoder
    }
}
