package it.polito.eurotransit.orders.service

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.entities.OutboxEntry
import it.polito.eurotransit.orders.entities.ProcessedRequest
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedRequestRepository
import it.polito.eurotransit.orders.dto.OrderPlacedEvent
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
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
        val existingReq = requestRepo.findById(req.idempotencyKey)
        
        if (existingReq != null) {
            return orderRepo.findById(existingReq.orderId) 
                ?: throw IllegalStateException("order not found for idempotency key")
        }

        return saveNewOrder(req)
    }

    private suspend fun saveNewOrder(req: OrderRequest): Order {
        val newOrderId = "ord-${UUID.randomUUID().toString().take(8)}"
        
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

        val processedReq = ProcessedRequest(
            idempotencyKey = req.idempotencyKey,
            orderId = newOrderId
        )

        val savedOrder = orderRepo.save(order)
        requestRepo.save(processedReq)
            
        val event = OrderPlacedEvent(
            eventId = "evt-${UUID.randomUUID()}",
            orderId = savedOrder.orderId,
            trainId = savedOrder.trainId,
            seatClass = savedOrder.seatClass,
            quantity = savedOrder.quantity
        )
        
        val payloadStr = objectMapper.writeValueAsString(event)
        
        val outboxEntry = OutboxEntry(
            eventId = event.eventId,
            topic = "eurotransit.order-placed",
            payload = payloadStr,
            createdAt = LocalDateTime.now()
        )
        outboxRepo.save(outboxEntry)

        return savedOrder
    }

    override suspend fun getOrderStatus(orderId: String): Order? {
        return orderRepo.findById(orderId)
    }

    override suspend fun getOrdersForUser(userId: String): List<Order> {
        return orderRepo.findByUserId(userId).toList()
    }
}