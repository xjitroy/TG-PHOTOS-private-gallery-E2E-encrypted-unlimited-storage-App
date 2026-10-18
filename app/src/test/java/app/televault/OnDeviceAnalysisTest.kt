package app.televault

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnDeviceAnalysisTest {
    @Test fun competingAnalysisOwnsOnlyOnePixelBufferAndCanceledWaitersNeverDecode() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        val first = launch {
            OnDeviceAnalysis.run {
                active++
                maximum = maxOf(maximum, active)
                entered.complete(Unit)
                release.await()
                active--
            }
        }
        entered.await()
        val canceled = launch { OnDeviceAnalysis.run { error("Canceled decode ran") } }
        yield()
        canceled.cancelAndJoin()
        val next = (0 until 100).map {
            launch {
                OnDeviceAnalysis.run {
                    active++
                    maximum = maxOf(maximum, active)
                    yield()
                    active--
                }
            }
        }
        release.complete(Unit)
        (next + first).joinAll()
        assertEquals(1, maximum)
        assertEquals(0, active)
    }

    @Test fun faceAndOcrNativeWorkDoNotOverlapAndFailureReleasesGate() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        coroutineScope {
            repeat(8) {
                launch(Dispatchers.Default) {
                    OnDeviceAnalysis.run {
                        val n = active.incrementAndGet()
                        peak.accumulateAndGet(n, ::maxOf)
                        delay(5)
                        active.decrementAndGet()
                    }
                }
            }
        }
        assertEquals(1, peak.get())
        try {
            OnDeviceAnalysis.run { throw IllegalStateException("failed inference") }
        } catch (_: IllegalStateException) { }
        assertEquals("next", OnDeviceAnalysis.run { "next" })
    }
}
