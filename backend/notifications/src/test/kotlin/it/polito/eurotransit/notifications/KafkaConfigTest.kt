package it.polito.eurotransit.notifications

import it.polito.eurotransit.notifications.config.KafkaConfig
import it.polito.eurotransit.notifications.dto.OrderConfirmedEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KafkaConfigTest {

    // Reproduces the actual wire shape post-fix: value-deserializer is
    // StringDeserializer (see application.yaml), so the ConsumerRecord's
    // value is the raw JSON string Orders' outbox published, exactly like
    // Kafka hands it to the listener container in production. Proves the
    // converter bean parses AND converts it into the listener's declared
    // type in one step, not just that the class compiles.
    @Test
    fun `converts a raw JSON string payload into the listener's declared event type`() {
        val converter = KafkaConfig().recordMessageConverter()

        val json = """
            {
              "event_id": "evt-ord-1-stage3",
              "order_id": "ord-1",
              "user_email": "user@example.com",
              "train_id": "TR-1",
              "seat_class": "standard",
              "quantity": 1,
              "amount": 58.84,
              "transaction_id": "txn-1"
            }
        """.trimIndent()

        val record = ConsumerRecord<String, Any>(
            "eurotransit.order-confirmed", 0, 0L, "evt-ord-1-stage3", json,
        )

        val message = converter.toMessage(
            record,
            null,
            null,
            OrderConfirmedEvent::class.java,
        )

        val payload = message.payload
        check(payload is OrderConfirmedEvent) { "expected OrderConfirmedEvent, got ${payload::class}" }
        assertEquals("ord-1", payload.order_id)
        assertEquals("user@example.com", payload.user_email)
        assertEquals(58.84, payload.amount)
    }
}
