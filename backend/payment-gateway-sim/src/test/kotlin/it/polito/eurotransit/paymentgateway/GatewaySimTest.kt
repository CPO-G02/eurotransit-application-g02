package it.polito.eurotransit.paymentgateway

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

// Stripe disabled so the no-header assertions exercise the local synth; the
// Stripe path is covered by StripeChargeGatewayTest.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.stripe.enabled=false"],
)
class GatewaySimTest @Autowired constructor(
    @Value("\${local.server.port}") private val port: Int,
) {

    private val client by lazy {
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(5))
            .build()
    }

    private fun charge(amount: Double) =
        mapOf("order_id" to "ord-1", "amount" to amount, "currency" to "EUR")

    @Test
    fun `authorizes amounts at or below the threshold`() {
        client.post().uri("/gateway/charge")
            .bodyValue(charge(120.0))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.decision").isEqualTo("AUTHORIZED")
    }

    @Test
    fun `declines amounts above the threshold`() {
        client.post().uri("/gateway/charge")
            .bodyValue(charge(750.0))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.decision").isEqualTo("DECLINED")
            .jsonPath("$.reason").isEqualTo("insufficient_funds")
    }

    @Test
    fun `X-Simulate-Failure returns 503`() {
        client.post().uri("/gateway/charge")
            .header("X-Simulate-Failure", "true")
            .bodyValue(charge(120.0))
            .exchange()
            .expectStatus().isEqualTo(503)
    }

    @Test
    fun `X-Simulate-Delay-Ms delays the response`() {
        val start = System.currentTimeMillis()
        client.post().uri("/gateway/charge")
            .header("X-Simulate-Delay-Ms", "500")
            .bodyValue(charge(120.0))
            .exchange()
            .expectStatus().isOk
        val elapsed = System.currentTimeMillis() - start
        assert(elapsed >= 500) { "expected the response to be delayed by >=500ms, was ${elapsed}ms" }
    }
}
