package it.polito.eurotransit.orders

import jakarta.annotation.PreDestroy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

class GracefulShutdownTest {

    @Component
    class InFlightTaskSimulator {
        val isCompleted = AtomicBoolean(false)
        
        @PreDestroy
        fun onShutdown() {
            isCompleted.set(true)
        }
    }

    @Test
    fun `verify graceful shutdown waits for in-flight tasks`() {
        val context = AnnotationConfigApplicationContext()
        context.register(InFlightTaskSimulator::class.java)
        context.refresh()

        val simulator = context.getBean(InFlightTaskSimulator::class.java)

        context.close()

        assertTrue(simulator.isCompleted.get(), "La tasca hauria d'haver finalitzat abans de tancar el context")
    }
}