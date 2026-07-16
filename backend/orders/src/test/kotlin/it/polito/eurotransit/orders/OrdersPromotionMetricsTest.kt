package it.polito.eurotransit.orders

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import it.polito.eurotransit.orders.metrics.OrdersPromotionMetrics
import it.polito.eurotransit.orders.metrics.OrdersPromotionCommitMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OrdersPromotionMetricsTest {
    @Test
    fun `promotion counters use bounded label-free names`() {
        val registry = SimpleMeterRegistry()
        val metrics = OrdersPromotionMetrics(registry)

        metrics.newRequestAccepted()
        metrics.replayedRequestAccepted()
        metrics.persistenceFailure()
        metrics.outboxCreated()
        metrics.outboxCreationFailure()
        metrics.outboxPublishFailure()

        assertEquals(1.0, registry.counter("eurotransit.orders.requests.accepted.new").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.requests.accepted.replayed").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.persistence.failures").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.outbox.created").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.outbox.creation.failures").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.outbox.publish.failures").count())
        registry.meters.forEach { meter -> assertEquals(0, meter.id.tags.size) }
    }

    @Test
    fun `positive counters are emitted only by after commit callbacks`() {
        val registry = SimpleMeterRegistry()
        val metrics = OrdersPromotionMetrics(registry)
        val commitMetrics = OrdersPromotionCommitMetrics(metrics)
        val newOrder = commitMetrics.newOrderSynchronization()
        val replay = commitMetrics.replaySynchronization()

        assertEquals(0.0, registry.counter("eurotransit.orders.requests.accepted.new").count())
        assertEquals(0.0, registry.counter("eurotransit.orders.outbox.created").count())
        assertEquals(0.0, registry.counter("eurotransit.orders.requests.accepted.replayed").count())

        newOrder.afterCommit().block()
        replay.afterCommit().block()

        assertEquals(1.0, registry.counter("eurotransit.orders.requests.accepted.new").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.outbox.created").count())
        assertEquals(1.0, registry.counter("eurotransit.orders.requests.accepted.replayed").count())
    }
}
