package app.televault

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnDeviceAnalysisTest {
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
