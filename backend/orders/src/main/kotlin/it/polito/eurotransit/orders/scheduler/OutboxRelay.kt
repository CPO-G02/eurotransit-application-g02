package it.polito.eurotransit.orders.scheduler

import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxRelay(
    private val outboxProcessor: OutboxProcessor
) {
    @Scheduled(fixedDelay = 1000) // poll every second
    fun pollOutbox() = runBlocking {
        outboxProcessor.processPendingMessages()
    }
}