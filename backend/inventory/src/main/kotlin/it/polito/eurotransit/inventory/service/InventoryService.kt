package it.polito.eurotransit.inventory.service

import it.polito.eurotransit.inventory.dto.OrderFailedEvent
import it.polito.eurotransit.inventory.dto.ReserveRequest
import it.polito.eurotransit.inventory.dto.ReserveResponse

interface InventoryService {

    /** Reserves seats atomically. Throws InsufficientSeatsException on 409. */
    suspend fun reserve(request: ReserveRequest): ReserveResponse

    /** Compensation: releases the reservation's seats if one exists and is still held. */
    suspend fun compensate(event: OrderFailedEvent)
}
