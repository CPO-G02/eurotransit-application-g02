package it.polito.eurotransit.orders.service

import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.domain.ProcessedRequest
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.ProcessedRequestRepository
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class OrderService(
    private val orderRepo: OrderRepository,
    private val requestRepo: ProcessedRequestRepository
) {

    // create new order with level 1 idempotency check
    fun createOrder(req: OrderRequest): Mono<Order> {
        return requestRepo.findById(req.idempotencyKey)
            .flatMap { existingReq ->
                // idempotent match found, return the original order
                orderRepo.findById(existingReq.orderId)
            }
            .switchIfEmpty(
                // new request, process normally
                saveNewOrder(req)
            )
    }

    // save new order and register idempotency key
    private fun saveNewOrder(req: OrderRequest): Mono<Order> {
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

        // save order first, then save idempotency record, then return the order
        return orderRepo.save(order)
            .delayUntil { requestRepo.save(processedReq) }
            // todo: trigger kafka 'order-placed' event here
    }

    // get order status for polling
    fun getOrderStatus(orderId: String): Mono<Order> {
        return orderRepo.findById(orderId)
    }
}