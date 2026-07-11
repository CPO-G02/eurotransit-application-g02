package it.polito.eurotransit.inventory

import it.polito.eurotransit.inventory.config.SecurityConfig
import it.polito.eurotransit.inventory.controllers.InventoryController
import it.polito.eurotransit.inventory.service.InventoryService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(InventoryController::class)
@Import(SecurityConfig::class)
class SecurityConfigTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var inventoryService: InventoryService

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    @Test
    fun `POST reserve rejects requests without bearer token`() {
        webTestClient.post()
            .uri("/reserve")
            .bodyValue("{}")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
