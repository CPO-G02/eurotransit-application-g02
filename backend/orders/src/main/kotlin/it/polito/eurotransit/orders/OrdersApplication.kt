package it.polito.eurotransit.orders

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// application entry point
// @EnableScheduling was missing entirely - every @Scheduled method (the
// existing OutboxRelay poller included) was silently never invoked without
// it. Spring does not warn about this; @Scheduled just becomes a no-op.
@SpringBootApplication
@EnableScheduling
class OrdersApplication

fun main(args: Array<String>) {
    runApplication<OrdersApplication>(*args)
    pritnln("OrdersApplication started")
}