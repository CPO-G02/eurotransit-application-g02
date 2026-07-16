package it.polito.eurotransit.orders.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import java.util.concurrent.Semaphore

@Configuration
class OrdersBackpressureConfig {

    @Bean
    fun ordersLoadSheddingFilter(
        @Value("\${app.backpressure.orders.enabled:true}")
        enabled: Boolean,
        @Value("\${app.backpressure.orders.max-concurrent-requests:20}")
        maxConcurrentRequests: Int,
        @Value("\${app.backpressure.orders.retry-after-seconds:1}")
        retryAfterSeconds: Long,
        meterRegistry: MeterRegistry
    ): WebFilter {
        require(maxConcurrentRequests > 0) {
            "app.backpressure.orders.max-concurrent-requests must be greater than 0"
        }

        val permits = Semaphore(maxConcurrentRequests)
        val rejectedCounter = meterRegistry.counter(
            "orders.backpressure.rejected",
            "route",
            "POST /api/v1/orders"
        )

        return WebFilter { exchange, chain ->
            if (!enabled || !exchange.isOrderCreationRequest()) {
                return@WebFilter chain.filter(exchange)
            }

            if (!permits.tryAcquire()) {
                rejectedCounter.increment()
                exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                exchange.response.headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
                return@WebFilter exchange.response.setComplete()
            }

            chain.filter(exchange)
                .doFinally { permits.release() }
        }
    }

    private fun ServerWebExchange.isOrderCreationRequest(): Boolean {
        return request.method == HttpMethod.POST &&
            request.path.pathWithinApplication().value().trimEnd('/') == "/api/v1/orders"
    }
}
