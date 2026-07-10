package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.InventoryClient
import it.polito.eurotransit.orders.domain.Order
import it.polito.eurotransit.orders.domain.OutboxEntry
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import it.polito.eurotransit.orders.dto.InventoryReserveResponse
import it.polito.eurotransit.orders.repository.OrderRepository
import it.polito.eurotransit.orders.repository.OutboxRepository
import it.polito.eurotransit.orders.repository.ProcessedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.kotlin.argumentCaptor
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
        
        whenever(processedEventRepo.existsById("evt-1")).thenReturn(false)
        
        whenever(inventoryClient.reserveSeats(any())).thenReturn(InventoryReserveResponse(reservation_id = "res-1", status = "RESERVED"))
        whenever(orderRepo.findById("ord-1")).thenReturn(Order(
            orderId = "ord-1",
            userId = "user-1",
            userEmail = "user@example.com",
            trainId = "T1",
            seatClass = "first",
            quantity = 1,
            amount = BigDecimal("100.00"),
            currency = "EUR",
            status = "PENDING"
        ))
        
        consumer.consumeOrderPlaced(message)

        val outboxCaptor = argumentCaptor<OutboxEntry>()
        verify(outboxRepo).save(outboxCaptor.capture())
        val payload = objectMapper.readTree(outboxCaptor.firstValue.payload)
        assertTrue(payload["event_id"].asText().startsWith("evt-"))
        assertTrue(payload["event_timestamp"].asText().isNotBlank())
        assertEquals("res-1", payload["reservation_id"].asText())
        verify(processedEventRepo).save(any())
    }
}
