package app.televault.ui
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import app.televault.domain.PhotoChromePolicy.Mode
import org.junit.Assert.*
import org.junit.Test
class BrowseChromeStateTest {
    @Test fun scrollRevealsControlsWithoutConsumingTheListsMovement() {
        var top = false
        val chrome = BrowseChromeState({ top }, { !top }, 2f)
        assertEquals(Offset.Zero, chrome.connection.onPostScroll(Offset(0f, -40f), Offset.Zero, NestedScrollSource.UserInput))
        assertEquals(Mode.HIDDEN, chrome.mode)
        chrome.connection.onPostScroll(Offset(0f, 60f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(Mode.COMPACT, chrome.mode)
        top = true
        chrome.connection.onPostScroll(Offset.Zero, Offset(0f, 2f), NestedScrollSource.UserInput)
        assertEquals(Mode.EXPANDED, chrome.mode)
    }

    @Test fun flingAndProgrammaticMovementDoNotRevealTheHeader() {
        val chrome = BrowseChromeState({ false }, { true }, 1f)
        chrome.connection.onPostScroll(Offset(0f, -20f), Offset.Zero, NestedScrollSource.UserInput)
        chrome.connection.onPostScroll(Offset(0f, 500f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(Mode.HIDDEN, chrome.mode)
    }

    @Test fun newFolderResetShowsIntroductionAgain() {
        val chrome = BrowseChromeState({ false }, { true }, 1f)
        chrome.connection.onPostScroll(Offset(0f, -20f), Offset.Zero, NestedScrollSource.UserInput)
        chrome.reset()
        assertEquals(Mode.EXPANDED, chrome.mode)
    }

    @Test fun reverseScrollCannotOverlayTheStillVisibleIntroduction() {
        var cleared = false
        val chrome = BrowseChromeState({ false }, { cleared }, 1f)
        chrome.connection.onPostScroll(Offset(0f, -100f), Offset.Zero, NestedScrollSource.UserInput)
        chrome.connection.onPostScroll(Offset(0f, 60f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(Mode.COMPACT, chrome.mode)
        assertFalse(chrome.compactVisible)
        cleared = true
        assertTrue(chrome.compactVisible)
        // The introduction re-enters on a fling: no new pointer event should be needed.
        cleared = false
        assertFalse(chrome.compactVisible)
    }

    @Test fun restoringNearTheTopOrSwitchingFoldersNeverShowsDuplicateControls() {
        val chrome = BrowseChromeState({ false }, { true }, 1f)
        assertFalse(chrome.compactVisible)
        repeat(50) {
            chrome.connection.onPostScroll(Offset(0f, -30f), Offset.Zero, NestedScrollSource.UserInput)
            chrome.connection.onPostScroll(Offset(0f, 40f), Offset.Zero, NestedScrollSource.UserInput)
            assertTrue(chrome.compactVisible)
            chrome.reset()
            assertFalse(chrome.compactVisible)
        }
    }
}
