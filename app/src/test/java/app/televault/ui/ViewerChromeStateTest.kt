package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class ViewerChromeStateTest {
    @Test fun wideVideoControlsUseFourSecondsWithoutChangingShortsOrDocumentTimeouts() {
        assertEquals(4000L, viewerChromeHideDelay(video = true, vertical = false))
        assertEquals(3000L, viewerChromeHideDelay(video = true, vertical = true))
        assertEquals(5000L, viewerChromeHideDelay(video = false, vertical = false))
    }

    @Test fun shortsStartHiddenAndEveryNextReelStaysHidden() {
        val chrome = ViewerChromeState("first", true)
        assertFalse(chrome.visible)
        chrome.toggle("first")
        assertTrue(chrome.visible)
        chrome.select("next", true)
        assertFalse(chrome.visible)
        chrome.select("next", true)
        assertFalse(chrome.visible)
        chrome.toggle("next")
        assertTrue(chrome.visible)
        chrome.toggle("next")
        assertFalse(chrome.visible)
    }

    @Test fun heldStripAndStationarySeekCannotExpire() {
        val chrome = ViewerChromeState("a", true)
        chrome.show("a")
        val beforePress = chrome.revision
        chrome.hold(true)
        chrome.expire(beforePress)
        chrome.expire(chrome.revision)
        assertTrue(chrome.visible)
        chrome.select("b", true)
        assertTrue(chrome.visible)
        chrome.hold(false)
        val released = chrome.revision
        chrome.expire(beforePress)
        assertTrue(chrome.visible)
        chrome.expire(released)
        assertFalse(chrome.visible)
    }

    @Test fun preparedOrDisposedNeighboursCannotRevealOrExtendCurrentControls() {
        val chrome = ViewerChromeState("a", true)
        chrome.select("b", true)
        val before = chrome.revision
        chrome.show("a")
        chrome.toggle("a")
        chrome.interact("a")
        assertFalse(chrome.visible)
        assertEquals(before, chrome.revision)
    }

    @Test fun optionalModeLeavesNormalViewerDefaultsIntact() {
        val chrome = ViewerChromeState("photo", false)
        assertTrue(chrome.visible)
        chrome.setVisible("photo", false)
        chrome.select("document", false)
        assertFalse(chrome.visible)
        chrome.select("document", true)
        assertFalse(chrome.visible)
        chrome.select("audio", true)
        assertFalse(chrome.visible)
        chrome.select("audio", false)
        assertTrue(chrome.visible)
    }

    @Test fun hiddenPhotoControlsSurviveBothSwipeDirectionsAndAutoHide() {
        val chrome = ViewerChromeState("one", false)
        chrome.toggle("one")
        repeat(100) {
            chrome.select("photo-$it", false)
            assertFalse(chrome.visible)
        }
        chrome.select("one", false)
        assertFalse(chrome.visible)
        chrome.toggle("one")
        assertTrue(chrome.visible)
        chrome.select("two", false)
        assertTrue(chrome.visible)
        chrome.expire(chrome.revision)
        chrome.select("one", false)
        assertFalse(chrome.visible)
    }

    @Test fun newInteractionInvalidatesPendingAutoHide() {
        val chrome = ViewerChromeState("a", true)
        chrome.show("a")
        val before = chrome.revision
        chrome.interact("a")
        chrome.expire(before)
        assertTrue(chrome.visible)
        chrome.expire(chrome.revision)
        assertFalse(chrome.visible)
    }
}
