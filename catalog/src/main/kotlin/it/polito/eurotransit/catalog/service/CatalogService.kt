package it.polito.eurotransit.catalog.service

import it.polito.eurotransit.catalog.dto.ProductResponse

/**
 * Read-only catalog queries. Declared as an interface so the web layer can be
 * tested against a lightweight fake without a database.
 */
interface CatalogService {

    suspend fun listProducts(): List<ProductResponse>

    /** Returns null when no train matches [trainId]; the web layer maps that to 404. */
    suspend fun getProduct(trainId: String): ProductResponse?
}
