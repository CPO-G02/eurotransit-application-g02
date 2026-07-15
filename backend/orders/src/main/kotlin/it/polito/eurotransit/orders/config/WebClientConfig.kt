package it.polito.eurotransit.orders.config

import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig {

    // Prototype: each client mutates its own builder (baseUrl, connector,
    // filter). A shared singleton would let one client's bearer filter and
    // response timeout leak into the other.
    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()
}
