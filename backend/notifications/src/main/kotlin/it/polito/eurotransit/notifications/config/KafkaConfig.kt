package it.polito.eurotransit.notifications.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.support.converter.RecordMessageConverter
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter

@Configuration
class KafkaConfig {

    // Pairs with value-deserializer: StringDeserializer in application.yaml.
    // Spring Boot auto-detects a single RecordMessageConverter bean and wires
    // it into the default listener container factory, where it parses each
    // record's raw JSON string AND converts it into whatever type the
    // matching @KafkaListener method declares (OrderConfirmedEvent vs
    // OrderFailedEvent), in one step. Without this, and with the old
    // JsonDeserializer (which produces a plain LinkedHashMap when the
    // producer sends no type header it can resolve - Orders' outbox has no
    // idea these DTOs exist), every order-confirmed/order-failed message was
    // silently dropped after exhausting retries: "Cannot convert from
    // LinkedHashMap to OrderConfirmedEvent". Confirmed live 2026-07-16.
    //
    // Jackson-3-based (this module depends on tools.jackson.module:
    // jackson-module-kotlin, not the classic com.fasterxml one) - the
    // deprecated Jackson-2 StringJsonMessageConverter's default ObjectMapper
    // doesn't discover that module and fails to construct these Kotlin data
    // classes (no default no-arg constructor). The no-arg JsonMapper this
    // builds internally auto-discovers every Jackson module on the
    // classpath, including jackson-module-kotlin. Confirmed via a failing
    // test first with the Jackson-2 converter.
    @Bean
    fun recordMessageConverter(): RecordMessageConverter = StringJacksonJsonMessageConverter()
}
