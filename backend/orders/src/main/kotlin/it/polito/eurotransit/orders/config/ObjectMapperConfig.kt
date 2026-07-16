package it.polito.eurotransit.orders.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jackson.autoconfigure.JacksonProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

@Configuration
@EnableConfigurationProperties(JacksonProperties::class)
class ObjectMapperConfig(
    private val applicationContext: ApplicationContext,
    private val jacksonProperties: JacksonProperties,
    private val builderProvider: ObjectProvider<Jackson2ObjectMapperBuilder>,
) {

    @Bean
    fun objectMapper(): ObjectMapper {
        val builder = builderProvider.getIfAvailable { Jackson2ObjectMapperBuilder.json() }
            .applicationContext(applicationContext)
        applyBootJacksonProperties(builder)
        return builder.build()
    }

    private fun applyBootJacksonProperties(builder: Jackson2ObjectMapperBuilder) {
        builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        if (jacksonProperties.isUseJackson2Defaults) {
            builder.featuresToDisable(
                MapperFeature.DEFAULT_VIEW_INCLUSION,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            )
        }
        jacksonProperties.dateFormat?.let(builder::simpleDateFormat)
        jacksonProperties.defaultPropertyInclusion?.let(builder::serializationInclusion)
        jacksonProperties.locale?.let(builder::locale)
        jacksonProperties.timeZone?.let(builder::timeZone)
        jacksonProperties.propertyNamingStrategy
            ?.let(::resolvePropertyNamingStrategy)
            ?.let(builder::propertyNamingStrategy)
        jacksonProperties.visibility.forEach { (accessor, visibility) ->
            builder.visibility(accessor, visibility)
        }
    }

    private fun resolvePropertyNamingStrategy(strategy: String): PropertyNamingStrategy =
        when (strategy) {
            "LOWER_CAMEL_CASE" -> PropertyNamingStrategies.LOWER_CAMEL_CASE
            "UPPER_CAMEL_CASE" -> PropertyNamingStrategies.UPPER_CAMEL_CASE
            "SNAKE_CASE" -> PropertyNamingStrategies.SNAKE_CASE
            "UPPER_SNAKE_CASE" -> PropertyNamingStrategies.UPPER_SNAKE_CASE
            "LOWER_CASE" -> PropertyNamingStrategies.LOWER_CASE
            "KEBAB_CASE" -> PropertyNamingStrategies.KEBAB_CASE
            "LOWER_DOT_CASE" -> PropertyNamingStrategies.LOWER_DOT_CASE
            else -> Class.forName(strategy).getDeclaredConstructor().newInstance() as PropertyNamingStrategy
        }
}
