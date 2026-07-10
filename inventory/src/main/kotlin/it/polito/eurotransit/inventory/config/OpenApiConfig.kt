package it.polito.eurotransit.inventory.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun inventoryOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("EuroTransit Inventory API")
                .description("Internal seat reservation service called synchronously by Orders.")
                .version("v1"),
        )
}
