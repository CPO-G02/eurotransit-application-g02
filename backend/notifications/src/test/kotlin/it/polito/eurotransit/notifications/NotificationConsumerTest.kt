package it.polito.eurotransit.notifications

import it.polito.eurotransit.notifications.dto.OrderConfirmedEvent
import it.polito.eurotransit.notifications.dto.OrderFailedEvent
import it.polito.eurotransit.notifications.service.NotificationConsumer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationConsumerTest {

    // Proves the real send path (composed message, real recipient/subject),
    // not just that the class compiles - this replaces a previous version
    // that only logged and never actually sent anything.
    @Test
    fun `handleOrderConfirmed sends a real email to the customer`() = runTest {
        val mailSender = mock(JavaMailSender::class.java)
        val consumer = NotificationConsumer(mailSender, fromAddress = "noreply@eurotransit.example")

        consumer.handleOrderConfirmed(
            OrderConfirmedEvent(
                event_id = "evt-1",
                order_id = "ord-1",
                user_email = "customer@example.com",
                train_id = "TR-1",
                seat_class = "standard",
                quantity = 1,
                amount = 58.84,
                transaction_id = "txn-1",
            ),
        )

        val captor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(mailSender).send(captor.capture())
        val sent = captor.value
        assertEquals("noreply@eurotransit.example", sent.from)
        assertEquals(listOf("customer@example.com"), sent.to?.toList())
        assertTrue(sent.subject!!.contains("ord-1"))
        assertTrue(sent.text!!.contains("TR-1"))
    }

    @Test
    fun `handleOrderFailed sends a real email with the failure reason`() = runTest {
        val mailSender = mock(JavaMailSender::class.java)
        val consumer = NotificationConsumer(mailSender, fromAddress = "noreply@eurotransit.example")

        consumer.handleOrderFailed(
            OrderFailedEvent(
                event_id = "evt-2",
                order_id = "ord-2",
                reservation_id = null,
                reason = "INSUFFICIENT_SEATS",
                user_email = "customer@example.com",
            ),
        )

        val captor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(mailSender).send(captor.capture())
        val sent = captor.value
        assertEquals(listOf("customer@example.com"), sent.to?.toList())
        assertTrue(sent.text!!.contains("INSUFFICIENT_SEATS"))
    }
}
