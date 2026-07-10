package it.polito.eurotransit.orders.scheduler

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class OutboxPollingRelayTest {
    private val outboxProcessor = mock(OutboxProcessor::class.java)
    
    private val relay = OutboxRelay(outboxProcessor)

    @Test
    fun `should call processor when polling`() = runBlocking {
        relay.pollOutbox()
        
        verify(outboxProcessor, times(1)).processPendingMessages()
    }
}