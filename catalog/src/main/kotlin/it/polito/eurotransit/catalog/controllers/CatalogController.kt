package it.polito.eurotransit.catalog.controllers

import it.polito.eurotransit.catalog.dto.ProductResponse
import it.polito.eurotransit.catalog.dto.ProductsResponse
import it.polito.eurotransit.catalog.exceptions.ProductNotFoundException
import it.polito.eurotransit.catalog.service.CatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public read-only catalog API (contract §1.1). The full path is mapped here
 * because Traefik routes /api/v1/catalog as a prefix without stripping it.
 */
@RestController
@RequestMapping("/api/v1/catalog/products")
class CatalogController(
    private val catalogService: CatalogService,
) {

    @GetMapping
    suspend fun listProducts(): ProductsResponse =
        ProductsResponse(catalogService.listProducts())

    @GetMapping("/{trainId}")
    suspend fun getProduct(@PathVariable trainId: String): ProductResponse =
        catalogService.getProduct(trainId) ?: throw ProductNotFoundException(trainId)
}
