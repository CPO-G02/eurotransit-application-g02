package it.polito.eurotransit.payments.controllers

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import it.polito.eurotransit.payments.dto.AuthorizeRequest
import it.polito.eurotransit.payments.dto.AuthorizeResponse
import it.polito.eurotransit.payments.dto.DeclinedResponse
import it.polito.eurotransit.payments.service.PaymentsService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Payments", description = "Internal payment authorization (called synchronously by Orders)")
@RestController
@RequestMapping("/api/v1/payments")
class PaymentsController(
    private val paymentsService: PaymentsService,
) {

    @Operation(
        summary = "Authorize a payment",
        description = "Authorizes the payment via the external gateway; 402 DECLINED with a reason on refusal.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Payment authorized"),
            ApiResponse(
                responseCode = "402",
                description = "Payment declined",
                content = [Content(schema = Schema(implementation = DeclinedResponse::class))],
            ),
        ],
    )
    @PostMapping("/authorize")
    suspend fun authorize(
        @RequestBody request: AuthorizeRequest,
    ): AuthorizeResponse =
        paymentsService.authorize(request)
}
