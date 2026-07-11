package it.polito.eurotransit.payments.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun paymentsOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("EuroTransit Payments API")
                .description("Internal payment authorization service called synchronously by Orders.")
                .version("v1"),
        )
}
