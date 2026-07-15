package it.polito.eurotransit.orders.scheduler

import it.polito.eurotransit.orders.metrics.OrderSloMetrics
import it.polito.eurotransit.orders.repositories.OrderRepository
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// Polls for the contract §4.3 SLI (orders PENDING past the 30s budget) the
// same way OutboxRelay polls for unsent outbox rows - a scheduled query
// updating a metric, not something derivable from a one-off event.
@Component
class StalePendingOrdersMonitor(
    private val orderRepo: OrderRepository,
    private val orderSloMetrics: OrderSloMetrics
) {
    @Scheduled(fixedDelay = 5000) // poll every 5s - well under the 30s budget it's measuring
    fun checkStalePendingOrders() = runBlocking {
        val cutoff = LocalDateTime.now().minusSeconds(30)
        val staleCount = orderRepo.countByStatusAndCreatedAtBefore("PENDING", cutoff)
        orderSloMetrics.updateStalePendingCount(staleCount)
    }
}
