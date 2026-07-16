package it.polito.eurotransit.orders.metrics

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

@Component
class OrdersPromotionCommitMetrics(
    private val metrics: OrdersPromotionMetrics
) {
    suspend fun recordNewOrderAfterCommit() = register(newOrderSynchronization())

    suspend fun recordReplayAfterCommit() = register(replaySynchronization())

    internal fun newOrderSynchronization(): TransactionSynchronization = afterCommit {
        metrics.newRequestAccepted()
        metrics.outboxCreated()
    }

    internal fun replaySynchronization(): TransactionSynchronization = afterCommit {
        metrics.replayedRequestAccepted()
    }

    private suspend fun register(synchronization: TransactionSynchronization) {
        val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
        check(manager.isSynchronizationActive) { "Orders promotion metrics require an active transaction" }
        manager.registerSynchronization(synchronization)
    }

    private fun afterCommit(action: () -> Unit): TransactionSynchronization =
        object : TransactionSynchronization {
            override fun afterCommit(): Mono<Void> = Mono.fromRunnable(action)
        }
}
