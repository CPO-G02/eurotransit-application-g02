package it.polito.eurotransit.orders.service

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedRequestRepository
import it.polito.eurotransit.orders.dto.OrderPlacedEvent
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
    private val objectMapper: ObjectMapper
) : OrderService {

    @Transactional
    override suspend fun createOrder(req: OrderRequest): Order {
        val newOrderId = "ord-${UUID.randomUUID().toString().take(8)}"

        if (requestRepo.insertIfAbsent(req.idempotencyKey, newOrderId) == 0) {
            val existingReq = requestRepo.findById(req.idempotencyKey)
                ?: throw IllegalStateException("idempotency key was claimed but no row was found")
            return orderRepo.findById(existingReq.orderId) 
                ?: throw IllegalStateException("order not found for idempotency key")
        }

        return saveNewOrder(req, newOrderId)
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
            
        val event = OrderPlacedEvent(
            eventId = "evt-${UUID.randomUUID()}",
            eventTimestamp = Instant.now().toString(),
            orderId = order.orderId,
            trainId = order.trainId,
            seatClass = order.seatClass,
            quantity = order.quantity
        )
        
        val payloadStr = objectMapper.writeValueAsString(event)

        outboxRepo.insert(
            eventId = event.eventId,
            topic = "eurotransit.order-placed",
            payload = payloadStr,
        )

        return order
    }

    override suspend fun getOrderStatus(orderId: String): Order? {
        return orderRepo.findById(orderId)
    }

    override suspend fun getOrdersForUser(userId: String): List<Order> {
        return orderRepo.findByUserId(userId).toList()
    }
}
