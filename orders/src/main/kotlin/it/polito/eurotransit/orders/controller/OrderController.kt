package it.polito.eurotransit.orders.controller

import it.polito.eurotransit.orders.dto.OrderConflictResponse
import it.polito.eurotransit.orders.dto.OrderRequest
import it.polito.eurotransit.orders.dto.OrderResponse
import it.polito.eurotransit.orders.dto.OrderStatusResponse
import it.polito.eurotransit.orders.service.OrderService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(private val orderService: OrderService) {

    // handle order creation with idempotency checks
    @PostMapping
    fun createOrder(@RequestBody request: OrderRequest): Mono<ResponseEntity<Any>> {
        return orderService.createOrder(request)
            .map { order ->
                if (order.status == "PENDING") {
                    // new order successfully created
                    ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(OrderResponse(order.orderId, order.status))
                } else {
                    // duplicate request already processed
                    ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(OrderConflictResponse(order.orderId, order.status, "order already processed"))
                }
            }
    }

    // poll order status by id
    @GetMapping("/{orderId}")
    fun getOrderStatus(@PathVariable orderId: String): Mono<ResponseEntity<OrderStatusResponse>> {
        return orderService.getOrderStatus(orderId)
            .map { order ->
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
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }
}