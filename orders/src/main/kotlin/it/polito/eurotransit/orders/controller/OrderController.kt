package it.polito.eurotransit.orders.controller

import it.polito.eurotransit.orders.dto.OrderConflictResponse
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.dto.OrderResponse
import it.polito.eurotransit.orders.dto.OrderStatusResponse
import it.polito.eurotransit.orders.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(private val orderService: OrderService) {

    // handle order creation natively with coroutines
    @PostMapping
    suspend fun createOrder(@RequestBody request: OrderRequest): ResponseEntity<Any> {
        val order = orderService.createOrder(request)
        
        return if (order.status == "PENDING") {
            ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(OrderResponse(order.orderId, order.status))
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(OrderConflictResponse(order.orderId, order.status, "order already processed"))
        }
    }

    // poll order status by id
    @GetMapping("/{orderId}")
    suspend fun getOrderStatus(@PathVariable orderId: String): ResponseEntity<OrderStatusResponse> {
        val order = orderService.getOrderStatus(orderId)
        
        return if (order != null) {
            ResponseEntity.ok(
                OrderStatusResponse(
                    orderId = order.orderId,
                    status = order.status,
                    trainId = order.trainId,
                    seatClass = order.seatClass,
                    quantity = order.quantity,
                    amount = order.amount,
                    currency = order.currency,
                    transactionId = order.transactionId,
                    createdAt = order.createdAt,
                    confirmedAt = order.confirmedAt
                )
            )
        } else {
            ResponseEntity.notFound().build()
        }
    }
}