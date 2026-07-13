package it.polito.eurotransit.payments.service

import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.entities.TransactionEntity
import it.polito.eurotransit.payments.repositories.ProcessedRequestRepository
import it.polito.eurotransit.payments.repositories.TransactionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

// Separate bean: @Transactional is proxy-based, a self-invoked method would run
// without a transaction.
@Component
class DecisionRecorder(
    private val transactionRepository: TransactionRepository,
    private val processedRequestRepository: ProcessedRequestRepository,
) {

    // Runs only after the gateway answered — no transaction across the remote
    // call. Losing the claim means a concurrent duplicate already recorded a
    // decision: discard ours, replay theirs. One transactions row per order.
    @Transactional
    suspend fun record(
        request: AuthorizeRequest,
        transactionId: String,
        status: String,
        reason: String?,
    ): TransactionEntity {
        if (processedRequestRepository.insertIfAbsent(request.idempotencyKey, transactionId) == 0) {
            val winner = processedRequestRepository.findById(request.idempotencyKey)
                ?: error("processed_requests row vanished for key ${request.idempotencyKey}")
            return transactionRepository.findByTransactionId(winner.transactionId)
                ?: error("transaction ${winner.transactionId} missing for key ${request.idempotencyKey}")
        }

        return transactionRepository.save(
            TransactionEntity(
                transactionId = transactionId,
                orderId = request.idempotencyKey,
                userId = request.userId,
                amount = request.amount,
                currency = request.currency,
                status = status,
                reason = reason,
            ),
        )
    }
}
