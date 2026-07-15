package it.polito.eurotransit.orders

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.polito.eurotransit.orders.config.OrdersBackpressureConfig
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrdersBackpressureConfigTest {

    private val meterRegistry = SimpleMeterRegistry()
    private val config = OrdersBackpressureConfig()

    @Test
    fun `POST orders returns 429 when concurrent request limit is exhausted`() {
        val filter = config.ordersLoadSheddingFilter(
            enabled = true,
            maxConcurrentRequests = 1,
            retryAfterSeconds = 2,
            meterRegistry = meterRegistry
        )

        val firstExchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/orders").build()
        )
        val firstSubscription = filter.filter(firstExchange, WebFilterChain { Mono.never() }).subscribe()

        try {
            val rejectedExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/orders").build()
            )

            filter.filter(rejectedExchange, WebFilterChain { Mono.empty() }).block()

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejectedExchange.response.statusCode)
            assertEquals("2", rejectedExchange.response.headers.getFirst(HttpHeaders.RETRY_AFTER))
            assertEquals(
                1.0,
                meterRegistry.counter("orders.backpressure.rejected", "route", "POST /api/v1/orders").count()
            )
        } finally {
            firstSubscription.dispose()
        }
    }

    @Test
    fun `non order creation requests bypass load shedding filter`() {
        val filter = config.ordersLoadSheddingFilter(
            enabled = true,
            maxConcurrentRequests = 1,
            retryAfterSeconds = 1,
            meterRegistry = meterRegistry
        )

        val firstExchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/orders").build()
        )
        val firstSubscription = filter.filter(firstExchange, WebFilterChain { Mono.never() }).subscribe()

        try {
            val bypassedExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders/ord-1").build()
            )

            filter.filter(bypassedExchange, WebFilterChain { exchange ->
                exchange.response.statusCode = HttpStatus.OK
                exchange.response.setComplete()
            }).block()

            assertEquals(HttpStatus.OK, bypassedExchange.response.statusCode)
            assertNull(bypassedExchange.response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        } finally {
            firstSubscription.dispose()
        }
    }
}
