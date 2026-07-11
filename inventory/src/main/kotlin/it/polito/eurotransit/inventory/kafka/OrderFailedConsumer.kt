package it.polito.eurotransit.inventory.kafka

import it.polito.eurotransit.inventory.dto.OrderFailedEvent
import it.polito.eurotransit.inventory.service.InventoryService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class OrderFailedConsumer(
    private val inventoryService: InventoryService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${app.kafka.topics.order-failed}"])
    suspend fun onOrderFailed(message: String) {
        val event = objectMapper.readValue(message, OrderFailedEvent::class.java)
        log.info("event=order_failed_received order_id={} reservation_id={}", event.orderId, event.reservationId)
        inventoryService.compensate(event)
    }
}
