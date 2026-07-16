package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.InventoryClient
import it.polito.eurotransit.orders.dto.InventoryReserveResponse
import it.polito.eurotransit.orders.entities.Order
import it.polito.eurotransit.orders.repositories.OrderRepository
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class Stage1ConsumerTest {

    private val inventoryClient = mock(InventoryClient::class.java)
    private val orderRepo = mock(OrderRepository::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage1Consumer(inventoryClient, orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should process order-placed and save to outbox on success`() = runBlocking {
        val message = """{"event_id": "evt-1", "order_id": "ord-1", "train_id": "T1", "seat_class": "first", "quantity": 1}"""
        
        whenever(processedEventRepo.insertIfAbsent("evt-1")).thenReturn(1)
        whenever(orderRepo.findById("ord-1")).thenReturn(order())
        
        whenever(inventoryClient.reserveSeats(any())).thenReturn(
            InventoryReserveResponse(reservation_id = "res-1", status = "RESERVED"),
        )
        
        consumer.consumeOrderPlaced(message)

        verify(outboxRepo).insert(
            eq("evt-ord-1-stage1"),
            eq("eurotransit.inventory-reserved"),
            argThat { payloadJson: String ->
            val payload = objectMapper.readTree(payloadJson)
            assertEquals("evt-ord-1-stage1", payload["event_id"].asText())
            assertEquals(true, payload.has("event_timestamp"))
            assertEquals("ord-1", payload["order_id"].asText())
            assertEquals("res-1", payload["reservation_id"].asText())
            assertEquals("user-1", payload["user_id"].asText())
            assertEquals(0, payload["amount"].decimalValue().compareTo(BigDecimal("100.00")))
            assertEquals("EUR", payload["currency"].asText())
            assertEquals(false, payload.has("eventId"))
            assertEquals(false, payload.has("reservationId"))
            assertEquals(false, payload.has("userId"))
            payload["event_id"].asText() == "evt-ord-1-stage1"
        })
        verify(processedEventRepo).insertIfAbsent("evt-1")
        Unit
    }

    private fun order() = Order(
        orderId = "ord-1",
        userId = "user-1",
        userEmail = "client@example.com",
        trainId = "T1",
        seatClass = "first",
        quantity = 1,
        amount = BigDecimal("100.00"),
        currency = "EUR",
        status = "PENDING",
    )
}   
