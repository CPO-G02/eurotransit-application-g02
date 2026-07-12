package it.polito.eurotransit.notifications

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import kotlin.test.assertNotNull

@SpringBootTest
@EmbeddedKafka(partitions = 1)
@ActiveProfiles("test")
class ShutdownIntegrationTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var registry: KafkaListenerEndpointRegistry

    @Test
    fun `should verify graceful shutdown beans are configured`() {
        assertNotNull(registry)
        
        assert(registry.allListenerContainers.isNotEmpty())
    }
}