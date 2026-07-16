package it.polito.eurotransit.orders.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class OrdersPromotionMetrics(registry: MeterRegistry) {
    private val newRequestsAccepted: Counter =
        registry.counter("eurotransit.orders.requests.accepted.new")
    // Temporary rollout compatibility. Remove after every environment runs an
    // image that exports requests.accepted.new and the Helm fallback is gone.
    private val legacyRequestsAccepted: Counter =
        registry.counter("eurotransit.orders.requests.accepted")
    private val replayedRequestsAccepted: Counter =
        registry.counter("eurotransit.orders.requests.accepted.replayed")
    private val persistenceFailures: Counter =
        registry.counter("eurotransit.orders.persistence.failures")
    private val outboxCreated: Counter =
        registry.counter("eurotransit.orders.outbox.created")
    private val outboxCreationFailures: Counter =
        registry.counter("eurotransit.orders.outbox.creation.failures")
    private val outboxPublishFailures: Counter =
        registry.counter("eurotransit.orders.outbox.publish.failures")

    fun newRequestAccepted() {
        newRequestsAccepted.increment()
        legacyRequestsAccepted.increment()
    }
    fun replayedRequestAccepted() = replayedRequestsAccepted.increment()
    fun persistenceFailure() = persistenceFailures.increment()
    fun outboxCreated() = outboxCreated.increment()
    fun outboxCreationFailure() = outboxCreationFailures.increment()
    fun outboxPublishFailure() = outboxPublishFailures.increment()
}
