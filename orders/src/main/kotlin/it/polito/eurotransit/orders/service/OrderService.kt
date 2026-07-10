package it.polito.eurotransit.orders.service

import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.dto.OrderRequest

interface OrderService {
    suspend fun createOrder(req: OrderRequest): Order
    suspend fun getOrderStatus(orderId: String): Order?
}