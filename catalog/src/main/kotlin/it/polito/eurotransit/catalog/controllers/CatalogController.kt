package it.polito.eurotransit.catalog.controllers

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import it.polito.eurotransit.catalog.dto.ErrorResponse
import it.polito.eurotransit.catalog.dto.ProductResponse
import it.polito.eurotransit.catalog.dto.ProductsResponse
import it.polito.eurotransit.catalog.exceptions.ProductNotFoundException
import it.polito.eurotransit.catalog.service.CatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Catalog", description = "Read-only product catalog: trains, seat classes and prices")
@RestController
@RequestMapping("/api/v1/catalog/products")
class CatalogController(
    private val catalogService: CatalogService,
) {

    @Operation(
        summary = "List products",
        description = "Returns every train with its seat classes and prices.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Products returned"),
        ],
    )
    @GetMapping
    suspend fun listProducts(): ProductsResponse =
        ProductsResponse(catalogService.listProducts())

    @Operation(
        summary = "Get product by train id",
        description = "Returns a single train, or 404 product_not_found if no train matches.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Product found"),
            ApiResponse(
                responseCode = "404",
                description = "Product not found",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping("/{trainId}")
    suspend fun getProduct(
        @Parameter(description = "Train identifier, e.g. TR-101") @PathVariable trainId: String,
    ): ProductResponse =
        catalogService.getProduct(trainId) ?: throw ProductNotFoundException(trainId)
}
