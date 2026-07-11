package it.polito.eurotransit.inventory.controllers

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import it.polito.eurotransit.inventory.dto.InsufficientSeatsResponse
import it.polito.eurotransit.inventory.dto.ReserveRequest
import it.polito.eurotransit.inventory.dto.ReserveResponse
import it.polito.eurotransit.inventory.service.InventoryService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Inventory", description = "Internal seat reservation (called synchronously by Orders)")
@RestController
class InventoryController(
    private val inventoryService: InventoryService,
) {

    @Operation(
        summary = "Reserve seats",
        description = "Atomically reserves seats; 409 INSUFFICIENT_SEATS if not enough remain.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Seats reserved"),
            ApiResponse(
                responseCode = "409",
                description = "Insufficient seats",
                content = [Content(schema = Schema(implementation = InsufficientSeatsResponse::class))],
            ),
        ],
    )
    @PostMapping("/reserve")
    suspend fun reserve(
        @RequestBody request: ReserveRequest,
    ): ReserveResponse =
        inventoryService.reserve(request)
}
