package it.polito.eurotransit.orders

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KafkaShutdownTest {

    @Component
    class KafkaConsumerSimulator {
        val isProcessed = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        fun consume() {
            runBlocking {
                delay(1000) 
                isProcessed.set(true)
                latch.countDown()
            }
        }
    }

    @Test
    fun `verify kafka message is processed during shutdown`() {
        val context = AnnotationConfigApplicationContext()
        context.register(KafkaConsumerSimulator::class.java)
        context.refresh()

        val consumer = context.getBean(KafkaConsumerSimulator::class.java)

        Thread { consumer.consume() }.start()

        context.close()

        val completed = consumer.latch.await(2, TimeUnit.SECONDS)
        
        assertTrue(completed && consumer.isProcessed.get(), "El missatge hauria d'haver estat processat")
    }
}