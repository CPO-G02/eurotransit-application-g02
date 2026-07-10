package it.polito.eurotransit.orders

import it.polito.eurotransit.orders.config.TestSimulators
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class KafkaShutdownTest {

    @Test
    fun `verify simulated consumer completes even if Spring context is closed`() = runBlocking {
        val context = AnnotationConfigApplicationContext()
        try {
            context.register(TestSimulators::class.java)
            context.refresh()
            
            val consumer = context.getBean(TestSimulators.KafkaConsumerSimulator::class.java)
            
            val job = launch(Dispatchers.Default) { consumer.consume() }
            
            context.close()
            
            withTimeout(1000L) {
                job.join()
            }
            
            assertTrue(consumer.isProcessed.get(), "The simulated consumer task should have completed")
        } finally {
            if (context.isActive) context.close()
        }
    }
}