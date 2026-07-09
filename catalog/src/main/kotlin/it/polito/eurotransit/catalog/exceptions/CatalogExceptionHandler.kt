package it.polito.eurotransit.catalog.exceptions

import it.polito.eurotransit.catalog.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

sealed class CatalogException(
    val status: HttpStatus,
    val errorCode: String,
    message: String,
) : RuntimeException(message)

class ProductNotFoundException(trainId: String) :
    CatalogException(HttpStatus.NOT_FOUND, "product_not_found", "No product with train_id '$trainId'")

@RestControllerAdvice
class CatalogExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(CatalogException::class)
    fun handleCatalogException(
        e: CatalogException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ErrorResponse> {
        log.warn(
            "event=catalog_error code={} status={} path={} message={}",
            e.errorCode, e.status.value(), exchange.request.path.value(), e.message,
        )
        return ResponseEntity.status(e.status).body(ErrorResponse(e.errorCode))
    }
}
