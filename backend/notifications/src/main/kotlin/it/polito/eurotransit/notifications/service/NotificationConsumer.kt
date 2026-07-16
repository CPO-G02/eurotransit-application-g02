package it.polito.eurotransit.notifications.service

import it.polito.eurotransit.notifications.dto.OrderConfirmedEvent
import it.polito.eurotransit.notifications.dto.OrderFailedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

@Service
class NotificationConsumer(
    private val mailSender: JavaMailSender,
    @Value("\${app.notifications.from}") private val fromAddress: String,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["eurotransit.order-confirmed"])
    suspend fun handleOrderConfirmed(event: OrderConfirmedEvent) {
        send(
            to = event.user_email,
            subject = "Your EuroTransit booking is confirmed - ${event.order_id}",
            text = """
                Hi,

                Your booking is confirmed.

                Order: ${event.order_id}
                Train: ${event.train_id}
                Class: ${event.seat_class}
                Seats: ${event.quantity}
                Amount charged: ${event.amount}
                Transaction: ${event.transaction_id}

                Thanks for booking with EuroTransit.
            """.trimIndent(),
        )
        logger.info("Sent confirmation email to ${event.user_email} for order ${event.order_id}")
    }

    @KafkaListener(topics = ["eurotransit.order-failed"])
    suspend fun handleOrderFailed(event: OrderFailedEvent) {
        send(
            to = event.user_email,
            subject = "Your EuroTransit booking could not be completed - ${event.order_id}",
            text = """
                Hi,

                We could not complete your booking.

                Order: ${event.order_id}
                Reason: ${event.reason}

                You have not been charged. Please try booking again.
            """.trimIndent(),
        )
        logger.info("Sent failure email to ${event.user_email} for order ${event.order_id}. reason: ${event.reason}")
    }

    // JavaMailSender.send is blocking network I/O - runs off the Kafka
    // listener thread so a slow/unreachable SMTP server can't stall consumer
    // polling.
    private suspend fun send(to: String, subject: String, text: String) = withContext(Dispatchers.IO) {
        val message = SimpleMailMessage().apply {
            setFrom(fromAddress)
            setTo(to)
            setSubject(subject)
            setText(text)
        }
        mailSender.send(message)
    }
}
