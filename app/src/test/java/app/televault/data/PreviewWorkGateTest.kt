package app.televault.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewWorkGateTest {
    @Test fun cancelledBurstDoesNotEnterNativeWorkWhileOldCallsFinish() = runBlocking {
        val gate = PreviewWorkGate(2)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        var starts = 0
        val old = List(2) {
            launch {
                gate.run {
                    active++
                    maximum = maxOf(maximum, active)
                    starts++
                    if (active == 2) entered.complete(Unit)
                    try {
                        withContext(NonCancellable) { release.await() }
                    } finally {
                        active--
                    }
                }
            }
        }
        entered.await()
        val stale = List(1000) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                gate.run {
                    starts++
                    active++
                    maximum = maxOf(maximum, active)
                    active--
                }
            }
        }
        stale.forEach { it.cancel() }
        old.forEach { it.cancel() }
        assertEquals(2, starts)
        release.complete(Unit)
        (old + stale).joinAll()
        gate.run { starts++ }
        assertEquals(3, starts)
        assertEquals(2, maximum)
        assertEquals(0, active)
    }

    @Test fun failedNativeCallCannotLeakItsPermit() = runBlocking {
        val gate = PreviewWorkGate(1)
        try {
            gate.run<Unit> { throw IllegalStateException("native failure") }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        withTimeout(1000) { gate.run { assertTrue(true) } }
    }
}
