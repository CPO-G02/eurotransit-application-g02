package it.polito.eurotransit.orders.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import it.polito.eurotransit.orders.client.PaymentClient
import it.polito.eurotransit.orders.dto.PaymentAuthorizeResponse
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

class Stage2ConsumerTest {

    private val paymentClient = mock(PaymentClient::class.java)
    private val orderRepo = mock(OrderRepository::class.java)
    private val outboxRepo = mock(OutboxRepository::class.java)
    private val processedEventRepo = mock(ProcessedEventRepository::class.java)
    private val objectMapper = ObjectMapper()
    
    private val consumer = Stage2Consumer(paymentClient, orderRepo, outboxRepo, processedEventRepo, objectMapper)

    @Test
    fun `should process inventory-reserved and save authorized to outbox`() = runBlocking {
        val message = """{
            "event_id": "evt-2", 
            "order_id": "ord-1", 
            "reservation_id": "res-1",
            "user_id": "user-1", 
            "amount": 100.0, 
            "currency": "EUR"
        }"""
        
        whenever(processedEventRepo.insertIfAbsent("evt-2")).thenReturn(1)
        whenever(orderRepo.findById("ord-1")).thenReturn(order())
        
        whenever(paymentClient.authorizePayment(any())).thenReturn(PaymentAuthorizeResponse(
            transaction_id = "tx-1", 
            status = "AUTHORIZED", 
            reason = null
        ))
        
        consumer.consumeInventoryReserved(message)
        
        verify(orderRepo).save(argThat { order: Order -> order.status == "RESERVED" })
        verify(outboxRepo).insert(
            eq("evt-ord-1-stage2"),
            eq("eurotransit.payment-authorized"),
            argThat { payloadJson: String ->
            val payload = objectMapper.readTree(payloadJson)
            assertEquals("evt-ord-1-stage2", payload["event_id"].asText())
            assertEquals(true, payload.has("event_timestamp"))
            assertEquals("ord-1", payload["order_id"].asText())
            assertEquals("tx-1", payload["transaction_id"].asText())
            assertEquals("100.0", payload["amount"].decimalValue().toPlainString())
            assertEquals("EUR", payload["currency"].asText())
            assertEquals(false, payload.has("eventId"))
            assertEquals(false, payload.has("transactionId"))
            payload["event_id"].asText() == "evt-ord-1-stage2"
        })
        verify(processedEventRepo).insertIfAbsent("evt-2")
        Unit
    }

    @Test
    fun `should propagate reservation id and decline reason to payment-failed outbox`() = runBlocking {
        val message = """{
            "event_id": "evt-2-decline",
            "order_id": "ord-1",
            "reservation_id": "res-1",
            "user_id": "user-1",
            "amount": 100.0,
            "currency": "EUR"
        }"""

        whenever(processedEventRepo.insertIfAbsent("evt-2-decline")).thenReturn(1)
        whenever(orderRepo.findById("ord-1")).thenReturn(order())
        whenever(paymentClient.authorizePayment(any())).thenReturn(
            PaymentAuthorizeResponse(
                transaction_id = null,
                status = "DECLINED",
                reason = "insufficient_funds",
            ),
        )

        consumer.consumeInventoryReserved(message)

        verify(outboxRepo).insert(
            eq("evt-ord-1-stage2"),
            eq("eurotransit.payment-failed"),
            argThat { payloadJson: String ->
            val payload = objectMapper.readTree(payloadJson)
            assertEquals("evt-ord-1-stage2", payload["event_id"].asText())
            assertEquals(true, payload.has("event_timestamp"))
            assertEquals("ord-1", payload["order_id"].asText())
            assertEquals("res-1", payload["reservation_id"].asText())
            assertEquals("insufficient_funds", payload["reason"].asText())
            assertEquals(false, payload.has("eventId"))
            assertEquals(false, payload.has("reservationId"))
            payload["event_id"].asText() == "evt-ord-1-stage2"
        })
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
