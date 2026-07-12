package it.polito.eurotransit.paymentgateway.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun paymentGatewayOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("EuroTransit Payment Gateway (simulated)")
                .description("Stand-in for the external payment processor, with controllable latency/failure injection.")
                .version("v1"),
        )
}
