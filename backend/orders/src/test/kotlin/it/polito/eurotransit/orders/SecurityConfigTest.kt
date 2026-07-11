package it.polito.eurotransit.orders

import it.polito.eurotransit.orders.config.SecurityConfig
import it.polito.eurotransit.orders.controllers.OrderController
import it.polito.eurotransit.orders.service.OrderService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(OrderController::class)
@Import(SecurityConfig::class)
class SecurityConfigTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var orderService: OrderService

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    @Test
    fun `POST orders rejects requests without bearer token`() {
        webTestClient.post()
            .uri("/api/v1/orders")
            .bodyValue("{}")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `GET order status rejects requests without bearer token`() {
        webTestClient.get()
            .uri("/api/v1/orders/ord-1")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
