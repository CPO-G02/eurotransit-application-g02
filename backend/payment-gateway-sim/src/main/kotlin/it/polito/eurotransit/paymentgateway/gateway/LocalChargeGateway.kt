package it.polito.eurotransit.paymentgateway.gateway

import it.polito.eurotransit.paymentgateway.dto.ChargeRequest
import it.polito.eurotransit.paymentgateway.dto.ChargeResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal


@Component
class LocalChargeGateway(
    @Value("\${app.gateway.decline-above}") private val declineAbove: BigDecimal,
) : ChargeGateway {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun charge(request: ChargeRequest): ChargeResponse =
        if (request.amount > declineAbove) {
            logger.info("event=charge_declined_local order_id={} amount={}", request.orderId, request.amount)
            ChargeResponse(decision = "DECLINED", reason = "insufficient_funds")
        } else {
            logger.info("event=charge_authorized_local order_id={} amount={}", request.orderId, request.amount)
            ChargeResponse(decision = "AUTHORIZED")
        }
}
