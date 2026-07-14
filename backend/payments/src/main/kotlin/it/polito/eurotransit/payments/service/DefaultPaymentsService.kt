package it.polito.eurotransit.payments.service

import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.dto.AuthorizeResponse
import it.polito.eurotransit.payments.entities.TransactionEntity
import it.polito.eurotransit.payments.exceptions.PaymentDeclinedException
import it.polito.eurotransit.payments.gateway.CIRCUIT_BREAKER_OPEN
import it.polito.eurotransit.payments.gateway.GatewayDecision
import it.polito.eurotransit.payments.gateway.PaymentGateway
import it.polito.eurotransit.payments.repositories.ProcessedRequestRepository
import it.polito.eurotransit.payments.repositories.TransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DefaultPaymentsService(
    private val paymentGateway: PaymentGateway,
    private val transactionRepository: TransactionRepository,
    private val processedRequestRepository: ProcessedRequestRepository,
    private val decisionRecorder: DecisionRecorder,
) : PaymentsService {

    private val log = LoggerFactory.getLogger(javaClass)

    // Not @Transactional as a whole: no transaction across the gateway call.
    // The writes that must agree are wrapped together in DecisionRecorder.
    override suspend fun authorize(request: AuthorizeRequest): AuthorizeResponse {
        val alreadyDecided = findRecordedDecision(request.idempotencyKey)
        if (alreadyDecided != null) {
            log.info(
                "event=authorize_deduplicated order_id={} transaction_id={} status={}",
                request.idempotencyKey, alreadyDecided.transactionId, alreadyDecided.status,
            )
            return respond(alreadyDecided, request.idempotencyKey)
        }

        val decision = paymentGateway.authorize(request)
        val transactionId = "txn-${UUID.randomUUID()}"

        if (decision is GatewayDecision.Declined && decision.reason == CIRCUIT_BREAKER_OPEN) {
            // The breaker answered, not the gateway: nothing to deduplicate,
            // and a claim here would stop retries from ever reaching the
            // recovered gateway. The row is still written for dashboards (§2.4).
            val declined = transactionRepository.save(
                newTransaction(request, transactionId, "DECLINED", decision.reason),
            )
            log.info(
                "event=payment_declined transaction_id={} order_id={} amount={} currency={} reason={}",
                declined.transactionId, request.idempotencyKey, request.amount, request.currency, decision.reason,
            )
            throw PaymentDeclinedException(decision.reason, request.idempotencyKey)
        }

        val recorded = when (decision) {
            is GatewayDecision.Authorized -> decisionRecorder.record(request, transactionId, "AUTHORIZED", null)
            is GatewayDecision.Declined -> decisionRecorder.record(request, transactionId, "DECLINED", decision.reason)
        }

        if (recorded.status == "AUTHORIZED") {
            log.info(
                "event=payment_authorized transaction_id={} order_id={} amount={} currency={}",
                recorded.transactionId, request.idempotencyKey, request.amount, request.currency,
            )
        } else {
            log.info(
                "event=payment_declined transaction_id={} order_id={} amount={} currency={} reason={}",
                recorded.transactionId, request.idempotencyKey, request.amount, request.currency, recorded.reason,
            )
        }
        return respond(recorded, request.idempotencyKey)
    }

    private suspend fun findRecordedDecision(idempotencyKey: String): TransactionEntity? {
        val claim = processedRequestRepository.findById(idempotencyKey) ?: return null
        return transactionRepository.findByTransactionId(claim.transactionId)
            ?: error("transaction ${claim.transactionId} missing for key $idempotencyKey")
    }

    private fun respond(transaction: TransactionEntity, orderId: String): AuthorizeResponse {
        if (transaction.status == "DECLINED") {
            throw PaymentDeclinedException(transaction.reason ?: "declined", orderId)
        }
        return AuthorizeResponse(transactionId = transaction.transactionId, status = "AUTHORIZED")
    }

    private fun newTransaction(
        request: AuthorizeRequest,
        transactionId: String,
        status: String,
        reason: String?,
    ) = TransactionEntity(
        transactionId = transactionId,
        orderId = request.idempotencyKey,
        userId = request.userId,
        amount = request.amount,
        currency = request.currency,
        status = status,
        reason = reason,
    )
}
