package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class VideoSurfaceHandoffTest {
    @Test fun ordinaryCreationAndRecompositionNeverDetachPlayback() {
        val handoff = VideoSurfaceHandoff()
        repeat(1000) { assertFalse(handoff.update(false, true)) }
    }

    @Test fun actualPipReturnRebindsExactlyOnce() {
        val handoff = VideoSurfaceHandoff()
        assertFalse(handoff.update(true, true))
        assertTrue(handoff.update(false, true))
        assertFalse(handoff.update(false, true))
    }

    @Test fun anOutgoingPageCannotRebindTheNewOwnersSurface() {
        val outgoing = VideoSurfaceHandoff()
        val incoming = VideoSurfaceHandoff()
        outgoing.update(true, true)
        assertFalse(outgoing.update(false, false))
        assertFalse(incoming.update(false, true))
        assertFalse(outgoing.update(false, true))
    }
}
