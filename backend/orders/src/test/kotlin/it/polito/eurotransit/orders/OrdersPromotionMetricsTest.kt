package it.polito.eurotransit.orders

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.polito.eurotransit.orders.metrics.OrdersPromotionMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OrdersPromotionMetricsTest {
    @Test
    fun `promotion counters use bounded label-free names`() {
        val registry = SimpleMeterRegistry()
        val metrics = OrdersPromotionMetrics(registry)

        metrics.requestAccepted()
        metrics.persistenceFailure()
        metrics.outboxCreated()
        metrics.outboxCreationFailure()
        metrics.outboxPublishFailure()

        assertEquals(1.0, registry.counter("eurotransit.orders.requests.accepted").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.persistence.failures").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.outbox.created").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.outbox.creation.failures").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.outbox.publish.failures").count())
        registry.meters.forEach { meter -> assertEquals(0, meter.id.tags.size) }
    }
}
