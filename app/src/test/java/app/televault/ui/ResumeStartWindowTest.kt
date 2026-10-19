package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class ResumeStartWindowTest {
    private fun target() = BrowseStartTarget({ true }, { true }, { false }, {})

    @Test fun tenSecondsStartsAtArrivalAndExpiresAtTheBoundary() {
        val state = ResumeStartWindow()
        val target = target()
        state.open("Albums/Camera", target, 5000)
        assertNotNull(state.current("Albums/Camera", target, 5000))
        assertNotNull(state.current("Albums/Camera", target, 14999))
        assertNull(state.current("Albums/Camera", target, 15000))
        assertNull(state.current("Albums/Camera", target, 4999))
    }

    @Test fun navigationCannotUseAnOldFoldersScrollAction() {
        val state = ResumeStartWindow()
        val target = target()
        state.open("Files/folder-one", target, 100)
        assertNull(state.current("Files/folder-two", target, 200))
        assertNull(state.current("Files/folder-one", target(), 200))
    }

    @Test fun aLateTimeoutCannotDismissANewForegroundOffer() {
        val state = ResumeStartWindow()
        val target = target()
        val old = state.open("Photos", target, 100)
        val next = state.open("Photos", target, 200)
        state.clear(old.token)
        assertEquals(next, state.current("Photos", target, 300))
        state.clear(next.token)
        assertNull(state.current("Photos", target, 300))
    }

    @Test fun dismissAndRepeatedClicksCannotReopenTheOffer() {
        val state = ResumeStartWindow()
        val target = target()
        val offer = state.open("Documents", target, 100)
        state.clear(offer.token)
        state.clear(offer.token)
        assertNull(state.current("Documents", target, 200))
    }
}
