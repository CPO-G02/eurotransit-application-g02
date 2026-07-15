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

class Stage4ConsumerTest {

    private val orderRepo = mock(OrderRepository::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage4Consumer(orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should mark order as FAILED when payment-failed event received`() = runBlocking {
        val orderId = "ord-1"
        val message = """{
            "event_id": "evt-4",
            "order_id": "$orderId",
            "reservation_id": "res-1",
            "reason": "insufficient_funds"
        }"""
        
        whenever(orderRepo.findById(orderId)).thenReturn(order())
        whenever(processedEventRepo.insertIfAbsent("evt-4")).thenReturn(1)
        
        consumer.consumePaymentFailed(message)
        
        verify(orderRepo).save(argThat { order -> order.status == "FAILED" })
        verify(outboxRepo).insert(
            eq("evt-ord-1-stage4"),
            eq("eurotransit.order-failed"),
            argThat { payloadJson: String ->
            val payload = objectMapper.readTree(payloadJson)
            assertEquals("evt-ord-1-stage4", payload["event_id"].asText())
            assertEquals(true, payload.has("event_timestamp"))
            assertEquals("ord-1", payload["order_id"].asText())
            assertEquals("res-1", payload["reservation_id"].asText())
            assertEquals("insufficient_funds", payload["reason"].asText())
            assertEquals("client@example.com", payload["user_email"].asText())
            assertEquals(false, payload.has("eventId"))
            assertEquals(false, payload.has("reservationId"))
            assertEquals(false, payload.has("userEmail"))
            payload["event_id"].asText() == "evt-ord-1-stage4"
        })
        verify(processedEventRepo).insertIfAbsent("evt-4")
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
