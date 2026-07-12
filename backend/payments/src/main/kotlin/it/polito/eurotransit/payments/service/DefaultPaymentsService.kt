package it.polito.eurotransit.payments.service

import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.dto.AuthorizeResponse
import it.polito.eurotransit.payments.entities.TransactionEntity
import it.polito.eurotransit.payments.exceptions.PaymentDeclinedException
import it.polito.eurotransit.payments.gateway.GatewayDecision
import it.polito.eurotransit.payments.gateway.PaymentGateway
import it.polito.eurotransit.payments.repositories.TransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DefaultPaymentsService(
    private val paymentGateway: PaymentGateway,
    private val transactionRepository: TransactionRepository,
) : PaymentsService {

    private val log = LoggerFactory.getLogger(javaClass)

    // Request-level idempotency (processed_requests dedup) is out of scope here:
    // a duplicated authorize currently charges again. See payments/CLAUDE.md.
    //
    // Deliberately NOT @Transactional: the single insert is atomic on its own,
    // a transaction would be held across the (soon remote) gateway call, and it
    // would roll back the DECLINED row when the 402 exception is thrown.
    override suspend fun authorize(request: AuthorizeRequest): AuthorizeResponse {
        val decision = paymentGateway.authorize(request)
        val transactionId = "txn-${UUID.randomUUID()}"

        when (decision) {
            is GatewayDecision.Authorized -> {
                saveTransaction(transactionId, request, "AUTHORIZED", reason = null)
                log.info(
                    "event=payment_authorized transaction_id={} order_id={} amount={} currency={}",
                    transactionId, request.idempotencyKey, request.amount, request.currency,
                )
                return AuthorizeResponse(transactionId = transactionId, status = "AUTHORIZED")
            }
            is GatewayDecision.Declined -> {
                saveTransaction(transactionId, request, "DECLINED", decision.reason)
                log.info(
                    "event=payment_declined transaction_id={} order_id={} amount={} currency={} reason={}",
                    transactionId, request.idempotencyKey, request.amount, request.currency, decision.reason,
                )
                throw PaymentDeclinedException(decision.reason, request.idempotencyKey)
            }
        }
    }

    private suspend fun saveTransaction(
        transactionId: String,
        request: AuthorizeRequest,
        status: String,
        reason: String?,
    ) {
        transactionRepository.save(
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
