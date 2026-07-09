package it.polito.eurotransit.catalog

import it.polito.eurotransit.catalog.dto.ProductResponse
import it.polito.eurotransit.catalog.dto.SeatClassDto
import it.polito.eurotransit.catalog.controllers.CatalogController
import it.polito.eurotransit.catalog.exceptions.CatalogExceptionHandler
import it.polito.eurotransit.catalog.service.CatalogService
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal
import java.time.Instant

/**
 * Standalone web test: exercises routing, the 404 mapping, and the snake_case
 * JSON contract without a Spring context or a database. The controller is bound
 * directly with a hand-written fake service, so no autoconfigure test slice,
 * mocking framework, or Testcontainers is required (full DB-backed integration
 * tests are deferred to Phase 2).
 */
class CatalogControllerTest {

    private val service = object : CatalogService {
        override suspend fun listProducts(): List<ProductResponse> = listOf(SAMPLE)
        override suspend fun getProduct(trainId: String): ProductResponse? =
            SAMPLE.takeIf { it.trainId == trainId }
    }

    private val client: WebTestClient = WebTestClient
        .bindToController(CatalogController(service))
        .controllerAdvice(CatalogExceptionHandler())
        .build()

    @Test
    fun `lists products with the snake_case contract payload`() {
        client.get().uri("/api/v1/catalog/products")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.products[0].train_id").isEqualTo("TR-101")
            .jsonPath("$.products[0].seat_classes[0]['class']").isEqualTo("standard")
            .jsonPath("$.products[0].seat_classes[0].available").isEqualTo(42)
    }

    @Test
    fun `returns a single product by train id`() {
        client.get().uri("/api/v1/catalog/products/TR-101")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.train_id").isEqualTo("TR-101")
            .jsonPath("$.origin").isEqualTo("Turin")
    }

    @Test
    fun `returns 404 product_not_found for an unknown train`() {
        client.get().uri("/api/v1/catalog/products/UNKNOWN")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error").isEqualTo("product_not_found")
    }

    companion object {
        private val SAMPLE = ProductResponse(
            trainId = "TR-101",
            origin = "Turin",
            destination = "Milan",
            departure = Instant.parse("2026-07-15T08:30:00Z"),
            seatClasses = listOf(
                SeatClassDto("standard", BigDecimal("25.00"), "EUR", 42),
                SeatClassDto("business", BigDecimal("45.50"), "EUR", 8),
            ),
        )
    }
}
