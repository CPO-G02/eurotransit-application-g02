package it.polito.eurotransit.paymentgateway.config

import it.polito.eurotransit.paymentgateway.gateway.ChargeGateway
import it.polito.eurotransit.paymentgateway.gateway.LocalChargeGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ChargeGatewayConfig {


    @Bean("chargeGateway")
    @ConditionalOnProperty(name = ["app.stripe.enabled"], havingValue = "false")
    fun localAsChargeGateway(localChargeGateway: LocalChargeGateway): ChargeGateway = localChargeGateway
}
