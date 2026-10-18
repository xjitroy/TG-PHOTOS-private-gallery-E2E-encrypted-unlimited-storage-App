package app.televault.data

import org.junit.Assert.*
import org.junit.Test

class PreviewOutputTest {
    @Test fun failureAfterCacheTakesOwnershipNeverRecyclesVisiblePixels() {
        val frame = Any()
        var renderer: Any? = null
        var recycled = false
        val output = PreviewOutput(frame) { recycled = true }
        assertThrows(OutOfMemoryError::class.java) {
            output.use { owned ->
                owned.publish {
                    renderer = it
                    throw OutOfMemoryError("Cache notification failed after publication")
                }
            }
        }
        output.close()
        assertSame(frame, renderer)
        assertFalse(recycled)
    }

    @Test fun rejectedAndUnpublishedFramesAreDiscardedOnceButAcceptedFramesRemainLive() {
        var discarded = 0
        val rejected = PreviewOutput(Any()) { discarded++ }
        rejected.use { it.publish { false } }
        rejected.close()
        assertEquals(1, discarded)
        val cancelled = PreviewOutput(Any()) { discarded++ }
        cancelled.close()
        cancelled.close()
        cancelled.publish {
            fail("Closed output cannot publish")
            true
        }
        assertEquals(2, discarded)
        PreviewOutput(Any()) { discarded++ }.use { it.publish { true } }
        assertEquals(2, discarded)
    }
}
