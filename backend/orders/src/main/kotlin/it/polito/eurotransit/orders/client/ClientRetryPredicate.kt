package it.polito.eurotransit.orders.client

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import kotlinx.coroutines.CancellationException
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException

fun isClientRetryableException(t: Throwable): Boolean {
    return when {
        t is CancellationException -> false
        t is CallNotPermittedException -> false
        t is WebClientRequestException -> true // transport-level: timeout, connection refused/reset, DNS
        t is WebClientResponseException && t.statusCode.is5xxServerError -> true
        else -> false
    }
}
