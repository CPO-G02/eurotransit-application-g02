package it.polito.eurotransit.orders.service

import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.domain.ProcessedRequest
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.ProcessedRequestRepository
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// kafka event dto for order-placed
data class OrderPlacedEvent(
    val eventId: String,
    val orderId: String,
    val trainId: String,
    val seatClass: String,
    val quantity: Int
)

@Service
class OrderService(
    private val orderRepo: OrderRepository,
    private val requestRepo: ProcessedRequestRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {

    // create new order with level 1 idempotency check
    @Transactional
    suspend fun createOrder(req: OrderRequest): Order {
        val existingReq = requestRepo.findById(req.idempotencyKey)
        
        if (existingReq != null) {
            // idempotent match found, return the original order
            return orderRepo.findById(existingReq.orderId) 
                ?: throw IllegalStateException("order not found for idempotency key")
        }

        // new request, process normally
        return saveNewOrder(req)
    }

    // save new order, register idempotency key, and emit kafka event
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

        // save to db sequentially (wrapped in the @Transactional boundary)
        val savedOrder = orderRepo.save(order)
        requestRepo.save(processedReq)
            
        val event = OrderPlacedEvent(
            eventId = "evt-${UUID.randomUUID()}",
            orderId = savedOrder.orderId,
            trainId = savedOrder.trainId,
            seatClass = savedOrder.seatClass,
            quantity = savedOrder.quantity
        )
        
        // emit event to kafka using orderId as the partition key
        kafkaTemplate.send("eurotransit.order-placed", event.orderId, event)

        return savedOrder
    }

    // get order status for polling
    suspend fun getOrderStatus(orderId: String): Order? {
        return orderRepo.findById(orderId)
    }
}