package it.polito.eurotransit.orders.service

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedRequestRepository
import it.polito.eurotransit.orders.dto.OrderPlacedEvent
import it.polito.eurotransit.orders.metrics.OrdersPromotionMetrics
import it.polito.eurotransit.orders.metrics.OrdersPromotionCommitMetrics
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class OrderServiceImpl(
    private val orderRepo: OrderRepository,
    private val requestRepo: ProcessedRequestRepository,
    private val outboxRepo: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val promotionMetrics: OrdersPromotionMetrics,
    private val commitMetrics: OrdersPromotionCommitMetrics
) : OrderService {

    @Transactional
    override suspend fun createOrder(req: OrderRequest): Order {
        val newOrderId = "ord-${UUID.randomUUID().toString().take(8)}"

        val claimed = try {
            requestRepo.insertIfAbsent(req.idempotencyKey, newOrderId)
        } catch (exception: Exception) {
            promotionMetrics.persistenceFailure()
            throw exception
        }

        return if (claimed == 0) {
            val existingOrder = try {
                val existingReq = requestRepo.findById(req.idempotencyKey)
                    ?: throw IllegalStateException("idempotency key was claimed but no row was found")
                orderRepo.findById(existingReq.orderId)
                    ?: throw IllegalStateException("order not found for idempotency key")
            } catch (exception: Exception) {
                promotionMetrics.persistenceFailure()
                throw exception
            }
            commitMetrics.recordReplayAfterCommit()
            existingOrder
        } else {
            val newOrder = saveNewOrder(req, newOrderId)
            commitMetrics.recordNewOrderAfterCommit()
            newOrder
        }
    }

    private suspend fun saveNewOrder(req: OrderRequest, newOrderId: String): Order {
        val order = Order(
            orderId = newOrderId,
            userId = req.userId,
            userEmail = req.userEmail,
            trainId = req.trainId,
            seatClass = req.seatClass,
            quantity = req.quantity,
            amount = req.amount,
            currency = req.currency,
            status = "PENDING"
        )

        try {
            orderRepo.insertNew(
                orderId = order.orderId,
                userId = order.userId,
                userEmail = order.userEmail,
                trainId = order.trainId,
                seatClass = order.seatClass,
                quantity = order.quantity,
                amount = order.amount,
                currency = order.currency,
                status = order.status,
                transactionId = order.transactionId,
                createdAt = order.createdAt,
                confirmedAt = order.confirmedAt,
            )
        } catch (exception: Exception) {
            promotionMetrics.persistenceFailure()
            throw exception
        }
            
        val event = OrderPlacedEvent(
            eventId = "evt-${UUID.randomUUID()}",
            eventTimestamp = Instant.now().toString(),
            orderId = order.orderId,
            trainId = order.trainId,
            seatClass = order.seatClass,
            quantity = order.quantity
        )
        
        try {
            val payloadStr = objectMapper.writeValueAsString(event)
            outboxRepo.insert(
                eventId = event.eventId,
                topic = "eurotransit.order-placed",
                payload = payloadStr,
            )
        } catch (exception: Exception) {
            promotionMetrics.outboxCreationFailure()
            throw exception
        }

        return order
    }

    override suspend fun getOrderStatus(orderId: String): Order? {
        return orderRepo.findById(orderId)
    }

    override suspend fun getOrdersForUser(userId: String): List<Order> {
        return orderRepo.findByUserId(userId).toList()
    }
}
