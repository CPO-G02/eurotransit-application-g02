package it.polito.eurotransit.catalog.exceptions

import it.polito.eurotransit.catalog.dto.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

class ProductNotFoundException(trainId: String) : RuntimeException("Product not found: $trainId")

@RestControllerAdvice
class CatalogExceptionHandler {

    @ExceptionHandler(ProductNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleProductNotFound(): ErrorResponse = ErrorResponse("product_not_found")
}
