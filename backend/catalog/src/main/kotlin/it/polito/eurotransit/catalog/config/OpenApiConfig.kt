package it.polito.eurotransit.catalog.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun catalogOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("EuroTransit Catalog API")
                .description("Read-only product catalog: trains, seat classes and prices.")
                .version("v1"),
        )
}
