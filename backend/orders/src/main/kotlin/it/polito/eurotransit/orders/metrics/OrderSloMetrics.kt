package it.polito.eurotransit.orders.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

// Backs the two SLIs from docs/eurotransit-contract.md §4 that have no
// existing metric to measure them (§4.1, §4.3) - see docs/ai-logs.md for
// why these were previously left unimplemented rather than fabricated.
@Component
class OrderSloMetrics(meterRegistry: MeterRegistry) {

    // §4.1: 99% of orders reach CONFIRMED within 800ms. Two histogram needs:
    // publishPercentileHistogram() for ad-hoc histogram_quantile() queries,
    // and an exact serviceLevelObjectives() bucket at 800ms so the SLI can
    // be computed the same way as §4.2 - a plain ratio (fast enough / total)
    // that multi-window burn-rate math already understands, rather than
    // comparing a percentile point-estimate against a threshold.
    private val confirmationLatencyTimer: Timer = Timer.builder("orders.confirmation.latency")
        .description("Time from order creation to CONFIRMED status (contract §4.1)")
        .publishPercentileHistogram()
        .serviceLevelObjectives(Duration.ofMillis(800))
        .register(meterRegistry)

    // §4.3: count of orders currently stuck in PENDING past the 30s
    // pipeline-completion budget. A point-in-time fact about current state,
    // not something derivable from an event counter - populated by
    // scheduler/StalePendingOrdersMonitor.kt polling the DB, the same
    // pattern already used for the outbox relay.
    private val stalePendingCount = AtomicLong(0)

    init {
        Gauge.builder("orders.pending.stale.count", stalePendingCount) { it.get().toDouble() }
            .description("Orders in PENDING for longer than the 30s pipeline-completion budget (contract §4.3)")
            .register(meterRegistry)
    }

    fun recordConfirmationLatency(duration: Duration) {
        confirmationLatencyTimer.record(duration)
    }

    fun updateStalePendingCount(count: Long) {
        stalePendingCount.set(count)
    }
}
