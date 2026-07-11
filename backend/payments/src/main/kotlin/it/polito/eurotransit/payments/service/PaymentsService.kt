package it.polito.eurotransit.payments.service

import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.dto.AuthorizeResponse

interface PaymentsService {

    /** Authorizes a payment via the gateway. Throws PaymentDeclinedException on 402. */
    suspend fun authorize(request: AuthorizeRequest): AuthorizeResponse
}
