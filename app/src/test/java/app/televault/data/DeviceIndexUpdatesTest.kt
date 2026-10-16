package app.televault.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIndexUpdatesTest {
    @Test fun changesDuringTraversalPublishCurrentResultThenOneFollowUp() = runBlocking {
        withTimeout(5000) {
            val events = Channel<Unit>(Channel.UNLIMITED)
            val started = Channel<Int>(Channel.UNLIMITED)
            val finish = Channel<Unit>()
            val published = Channel<Int>(Channel.UNLIMITED)
            var scans = 0
            var cancelled = 0
            val worker = launch {
                collectDeviceIndexUpdates(events.receiveAsFlow()) {
                    val number = ++scans
                    started.send(number)
                    try {
                        if (number == 1) finish.receive()
                        published.send(number)
                    } catch (e: CancellationException) {
                        cancelled++
                        throw e
                    }
                }
            }
            events.send(Unit)
            assertEquals(1, started.receive())
            repeat(20) { events.send(Unit) }
            finish.send(Unit)
            assertEquals(1, published.receive())
            assertEquals(2, published.receive())
            worker.cancelAndJoin()
            assertEquals(2, scans)
            assertEquals(0, cancelled)
        }
    }

    @Test fun leavingDeviceBrowsingCancelsTraversalWithoutPublishingAnIncompleteResult() = runBlocking {
        withTimeout(5000) {
            val events = Channel<Unit>(Channel.UNLIMITED)
            val started = Channel<Unit>()
            val finish = Channel<Unit>()
            var published = false
            var cancelled = false
            val worker = launch {
                collectDeviceIndexUpdates(events.receiveAsFlow()) {
                    try {
                        started.send(Unit)
                        finish.receive()
                        published = true
                    } finally {
                        cancelled = true
                    }
                }
            }
            events.send(Unit)
            started.receive()
            worker.cancelAndJoin()
            assertEquals(false, published)
            assertEquals(true, cancelled)
        }
    }
}
