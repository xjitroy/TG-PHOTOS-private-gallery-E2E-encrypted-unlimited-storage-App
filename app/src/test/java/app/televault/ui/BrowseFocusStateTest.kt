package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class BrowseFocusStateTest {
    @Test fun highlightRequiresTheCurrentLocatedTarget() {
        val focus = BrowseFocusState()
        focus.request("photo")
        val token = focus.revision
        focus.located("other", token)
        assertNull(focus.highlightedId)
        focus.located("photo", token)
        assertEquals("photo", focus.highlightedId)
        assertNull(focus.pendingId)
        focus.clearHighlight(token)
        assertNull(focus.highlightedId)
    }

    @Test fun staleScrollAndTimerCannotReplaceNewFileOrFolderTarget() {
        val focus = BrowseFocusState()
        focus.request("video")
        val old = focus.revision
        focus.request("pdf")
        focus.located("video", old)
        assertNull(focus.highlightedId)
        focus.located("pdf", focus.revision)
        focus.clearHighlight(old)
        assertEquals("pdf", focus.highlightedId)
    }

    @Test fun disabledOptionCancelsPendingAndVisibleHighlights() {
        val focus = BrowseFocusState()
        focus.request("audio")
        val old = focus.revision
        focus.request(null)
        focus.located("audio", old)
        assertNull(focus.pendingId)
        assertNull(focus.highlightedId)
    }
}
