package it.polito.eurotransit.inventory.exceptions

import it.polito.eurotransit.inventory.dto.InsufficientSeatsResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

sealed class InventoryException(
    val status: HttpStatus,
    message: String,
) : RuntimeException(message)

class InsufficientSeatsException(trainId: String, seatClass: String, quantity: Int) :
    InventoryException(
        HttpStatus.CONFLICT,
        "Not enough '$seatClass' seats on '$trainId' for quantity $quantity",
    )

@RestControllerAdvice
class InventoryExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InsufficientSeatsException::class)
    fun handleInsufficientSeats(
        e: InsufficientSeatsException,
        exchange: ServerWebExchange,
    ): ResponseEntity<InsufficientSeatsResponse> {
        log.warn(
            "event=inventory_error status={} path={} message={}",
            e.status.value(), exchange.request.path.value(), e.message,
        )
        return ResponseEntity.status(e.status).body(InsufficientSeatsResponse("INSUFFICIENT_SEATS"))
    }
}
