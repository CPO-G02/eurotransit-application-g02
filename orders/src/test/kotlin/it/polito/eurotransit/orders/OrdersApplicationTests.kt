package it.polito.eurotransit.orders

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@Disabled("Disabled in CI until Testcontainers (Kafka & PostgreSQL) are configured for Phase 2")
class OrdersApplicationTests {

    @Test
    fun contextLoads() {
    }
}