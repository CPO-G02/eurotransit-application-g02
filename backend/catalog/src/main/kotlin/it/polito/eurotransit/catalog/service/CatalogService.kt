package it.polito.eurotransit.catalog.service

import it.polito.eurotransit.catalog.dto.ProductResponse


interface CatalogService {

    suspend fun listProducts(): List<ProductResponse>

    suspend fun getProduct(trainId: String): ProductResponse?
}
