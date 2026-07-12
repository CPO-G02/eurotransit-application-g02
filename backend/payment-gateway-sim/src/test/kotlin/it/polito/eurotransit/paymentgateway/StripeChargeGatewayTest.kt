package it.polito.eurotransit.paymentgateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import it.polito.eurotransit.paymentgateway.dto.ChargeRequest
import it.polito.eurotransit.paymentgateway.gateway.StripeChargeGateway
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WireMock stands in for api.stripe.com so the Stripe mapping is covered without
// live credentials; the small timeout-ms keeps the timeout case fast.
@SpringBootTest(
    properties = [
        "app.stripe.enabled=true",
        "app.stripe.secret-key=sk_test_dummy",
        "app.stripe.payment-method=pm_card_visa",
        "app.stripe.timeout-ms=500",
    ],
)
class StripeChargeGatewayTest @Autowired constructor(
    private val stripeGateway: StripeChargeGateway,
) {

    private fun request() = ChargeRequest(orderId = "ord-1", amount = BigDecimal("120.00"), currency = "EUR")

    companion object {
        @JvmStatic
        val stripe = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun start() = stripe.start()

        @JvmStatic
        @AfterAll
        fun stop() = stripe.stop()

        @JvmStatic
        @DynamicPropertySource
        fun stripeUrl(registry: DynamicPropertyRegistry) {
            registry.add("app.stripe.base-url") { "http://localhost:${stripe.port()}" }
        }
    }

    @BeforeEach
    fun reset() = stripe.resetAll()

    @Test
    fun `succeeded PaymentIntent maps to AUTHORIZED`() {
        stripe.stubFor(
            post(urlEqualTo("/v1/payment_intents"))
                .willReturn(okJson("""{"id":"pi_1","status":"succeeded"}""")),
        )

        val response = runBlocking { stripeGateway.charge(request()) }

        assertEquals("AUTHORIZED", response.decision)
        assertEquals(null, response.reason)
    }

    @Test
    fun `402 card_declined maps to DECLINED with the decline_code reason`() {
        stripe.stubFor(
            post(urlEqualTo("/v1/payment_intents")).willReturn(
                aResponse()
                    .withStatus(402)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """{"error":{"type":"card_error","code":"card_declined",""" +
                            """"decline_code":"insufficient_funds","message":"Your card was declined."}}""",
                    ),
            ),
        )

        val response = runBlocking { stripeGateway.charge(request()) }

        assertEquals("DECLINED", response.decision)
        assertEquals("insufficient_funds", response.reason)
    }

    @Test
    fun `request carries the idempotency key, auth, payment method and confirm`() {
        stripe.stubFor(
            post(urlEqualTo("/v1/payment_intents"))
                .willReturn(okJson("""{"id":"pi_1","status":"succeeded"}""")),
        )

        runBlocking { stripeGateway.charge(request()) }

        stripe.verify(
            postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo("ord-1"))
                .withHeader("Authorization", equalTo("Bearer sk_test_dummy"))
                .withHeader("Stripe-Version", matching(".+"))
                .withRequestBody(containing("payment_method=pm_card_visa"))
                .withRequestBody(containing("confirm=true")),
        )
    }

    @Test
    fun `a 5xx from Stripe propagates as a failure, not a fabricated decision`() {
        stripe.stubFor(
            post(urlEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(500)),
        )

        val outcome = runBlocking { runCatching { stripeGateway.charge(request()) } }

        assertTrue(outcome.isFailure, "a 5xx from Stripe must surface as a thrown failure")
    }

    @Test
    fun `a Stripe call slower than the timeout propagates as a failure`() {
        stripe.stubFor(
            post(urlEqualTo("/v1/payment_intents")).willReturn(
                okJson("""{"id":"pi_1","status":"succeeded"}""").withFixedDelay(1_500),
            ),
        )

        val outcome = runBlocking { runCatching { stripeGateway.charge(request()) } }

        assertTrue(outcome.isFailure, "a call slower than app.stripe.timeout-ms must time out and throw")
    }
}
