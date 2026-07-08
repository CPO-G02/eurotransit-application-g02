package it.polito.eurotransit.notifications.service

import it.polito.eurotransit.notifications.dto.OrderConfirmedEvent
import it.polito.eurotransit.notifications.dto.OrderFailedEvent
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class NotificationConsumer {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["eurotransit.order-confirmed"])
    suspend fun handleOrderConfirmed(event: OrderConfirmedEvent) {
        // simulate email generation delay (cancellable via SIGTERM)
        delay(500) 
        logger.info("Sending confirmation email to ${event.user_email} for order ${event.order_id}")
    }

    @KafkaListener(topics = ["eurotransit.order-failed"])
    suspend fun handleOrderFailed(event: OrderFailedEvent) {
        // simulate email generation delay (cancellable via SIGTERM)
        delay(500)
        logger.info("Sending failure email to ${event.user_email} for order ${event.order_id}. reason: ${event.reason}")
    }
}