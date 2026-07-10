package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.InventoryClient
import it.polito.eurotransit.orders.dto.InventoryReserveRequest
import it.polito.eurotransit.orders.dto.InventoryReserveResponse
import it.polito.eurotransit.orders.repositories.OutboxRepository
import it.polito.eurotransit.orders.repositories.ProcessedEventRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

class Stage1ConsumerTest {

    private val inventoryClient = mock(InventoryClient::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage1Consumer(inventoryClient, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should process order-placed and save to outbox on success`() = runBlocking {
        val message = """{"event_id": "evt-1", "order_id": "ord-1", "train_id": "T1", "seat_class": "first", "quantity": 1}"""
        
        whenever(processedEventRepo.existsById("evt-1")).thenReturn(false)
        
        whenever(inventoryClient.reserveSeats(any())).thenReturn(InventoryReserveResponse(status = "RESERVED"))
        
        consumer.consumeOrderPlaced(message)

        verify(outboxRepo).save(any())
        verify(processedEventRepo).save(any())
    }
}   