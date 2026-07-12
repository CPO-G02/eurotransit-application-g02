package it.polito.eurotransit.orders.config

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.delay
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.concurrent.atomic.AtomicBoolean

@TestConfiguration
class TestSimulators {

    class InFlightTaskSimulator {
        val isCompleted = AtomicBoolean(false)
        @PreDestroy
        fun onShutdown() {
            Thread.sleep(50)
            isCompleted.set(true)
        }
    }

    class KafkaConsumerSimulator {
        val isProcessed = AtomicBoolean(false)
        suspend fun consume() {
            delay(50)
            isProcessed.set(true)
        }
    }

    @Bean
    fun inFlightTaskSimulator() = InFlightTaskSimulator()

    @Bean
    fun kafkaConsumerSimulator() = KafkaConsumerSimulator()
}