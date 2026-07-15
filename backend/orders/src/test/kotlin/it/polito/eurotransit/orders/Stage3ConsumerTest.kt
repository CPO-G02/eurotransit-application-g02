package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
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

class Stage3ConsumerTest {

    private val orderRepo = mock(OrderRepository::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage3Consumer(orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should confirm order when payment-authorized event received`() = runBlocking {
        val orderId = "ord-1"
        val message = """{"event_id": "evt-3", "order_id": "$orderId", "transaction_id": "tx-1"}"""
        
        whenever(orderRepo.findById(orderId)).thenReturn(order())
        whenever(processedEventRepo.insertIfAbsent("evt-3")).thenReturn(1)
        
        consumer.consumePaymentAuthorized(message)
        
        verify(orderRepo).save(argThat { order: Order ->
            order.status == "CONFIRMED" && order.transactionId == "tx-1"
        })
        verify(outboxRepo).insert(
            eq("evt-ord-1-stage3"),
            eq("eurotransit.order-confirmed"),
            argThat { payloadJson: String ->
            val payload = objectMapper.readTree(payloadJson)
            assertEquals("evt-ord-1-stage3", payload["event_id"].asText())
            assertEquals(true, payload.has("event_timestamp"))
            assertEquals("ord-1", payload["order_id"].asText())
            assertEquals("client@example.com", payload["user_email"].asText())
            assertEquals("T1", payload["train_id"].asText())
            assertEquals("first", payload["seat_class"].asText())
            assertEquals(1, payload["quantity"].asInt())
            assertEquals(0, payload["amount"].decimalValue().compareTo(BigDecimal("100.00")))
            assertEquals("tx-1", payload["transaction_id"].asText())
            assertEquals(false, payload.has("eventId"))
            assertEquals(false, payload.has("userEmail"))
            assertEquals(false, payload.has("transactionId"))
            payload["event_id"].asText() == "evt-ord-1-stage3"
        })
        verify(processedEventRepo).insertIfAbsent("evt-3")
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
        status = "RESERVED",
    )
}
