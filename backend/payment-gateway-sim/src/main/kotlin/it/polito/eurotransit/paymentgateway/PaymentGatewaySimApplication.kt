package it.polito.eurotransit.paymentgateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PaymentGatewaySimApplication

fun main(args: Array<String>) {
	runApplication<PaymentGatewaySimApplication>(*args)
}
