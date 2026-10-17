package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class EphemeralImageSlotTest {
    private class Pixels(var wiped: Int = 0, var discarded: Int = 0)
    private fun slot() = EphemeralImageSlot<Pixels>({ it.wiped++ }, { it.discarded++ })

    @Test fun publishedPixelsAreWipedWithoutInvalidatingRendererMemory() {
        val slot = slot()
        val pixels = Pixels()
        slot.offer(pixels)
        assertSame(pixels, slot.publish())
        slot.retire()
        slot.retire()
        assertEquals(1, pixels.wiped)
        assertEquals(0, pixels.discarded)
        assertNull(slot.publish())
    }

    @Test fun cancelledBeforePublicationReleasesUnrenderedImage() {
        val slot = slot()
        val pixels = Pixels()
        slot.offer(pixels)
        slot.retire()
        assertNull(slot.publish())
        assertEquals(1, pixels.wiped)
        assertEquals(1, pixels.discarded)
    }

    @Test fun decodeThatFinishesAfterDisposalCannotPublishOrLeak() {
        val slot = slot()
        val pixels = Pixels()
        slot.retire()
        slot.offer(pixels)
        assertNull(slot.publish())
        assertEquals(1, pixels.wiped)
        assertEquals(1, pixels.discarded)
    }
}
