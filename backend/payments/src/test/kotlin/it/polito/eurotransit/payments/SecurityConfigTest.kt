package it.polito.eurotransit.payments

import it.polito.eurotransit.payments.config.SecurityConfig
import it.polito.eurotransit.payments.controllers.PaymentsController
import it.polito.eurotransit.payments.service.PaymentsService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(PaymentsController::class)
@Import(SecurityConfig::class)
class SecurityConfigTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var paymentsService: PaymentsService

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    @Test
    fun `POST authorize rejects requests without a bearer token`() {
        webTestClient.post()
            .uri("/api/v1/payments/authorize")
            .bodyValue("{}")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
