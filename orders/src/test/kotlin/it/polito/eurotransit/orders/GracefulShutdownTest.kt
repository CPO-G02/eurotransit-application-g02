    package it.polito.eurotransit.orders

    import it.polito.eurotransit.orders.config.TestSimulators
    import org.junit.jupiter.api.Assertions.assertTrue
    import org.junit.jupiter.api.Test
    import org.springframework.context.annotation.AnnotationConfigApplicationContext

    class GracefulShutdownTest {

        @Test
        fun `verify PreDestroy hooks run on context close`() {
            val context = AnnotationConfigApplicationContext()
            try {
                context.register(TestSimulators::class.java)
                context.refresh()
                
                val simulator = context.getBean(TestSimulators.InFlightTaskSimulator::class.java)
                context.close()
                
                assertTrue(simulator.isCompleted.get(), "@PreDestroy should have been invoked before the context closed")
            } finally {
                if (context.isActive) context.close()
            }
        }
    }