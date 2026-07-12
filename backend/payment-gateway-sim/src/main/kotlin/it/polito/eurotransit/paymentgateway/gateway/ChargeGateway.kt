package it.polito.eurotransit.paymentgateway.gateway

import it.polito.eurotransit.paymentgateway.dto.ChargeRequest
import it.polito.eurotransit.paymentgateway.dto.ChargeResponse

interface ChargeGateway {
    suspend fun charge(request: ChargeRequest): ChargeResponse
}
