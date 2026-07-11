package it.polito.eurotransit.payments.exceptions

import it.polito.eurotransit.payments.dto.DeclinedResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

sealed class PaymentsException(
    val status: HttpStatus,
    message: String,
) : RuntimeException(message)

class PaymentDeclinedException(val reason: String, orderId: String) :
    PaymentsException(HttpStatus.PAYMENT_REQUIRED, "Payment declined for order '$orderId': $reason")

@RestControllerAdvice
class PaymentsExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PaymentDeclinedException::class)
    fun handlePaymentDeclined(
        e: PaymentDeclinedException,
        exchange: ServerWebExchange,
    ): ResponseEntity<DeclinedResponse> {
        log.warn(
            "event=payments_error status={} path={} message={}",
            e.status.value(), exchange.request.path.value(), e.message,
        )
        return ResponseEntity.status(e.status).body(DeclinedResponse("DECLINED", e.reason))
    }
}
