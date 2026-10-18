package app.televault.ui
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import app.televault.domain.PhotoChromePolicy.Mode
import org.junit.Assert.*
import org.junit.Test
class BrowseChromeStateTest {
    @Test fun scrollRevealsControlsWithoutConsumingTheListsMovement() {
        var top = false
        val chrome = BrowseChromeState({ top }, 2f)
        assertEquals(Offset.Zero, chrome.connection.onPostScroll(Offset(0f, -40f), Offset.Zero, NestedScrollSource.UserInput))
        assertEquals(Mode.HIDDEN, chrome.mode)
        chrome.connection.onPostScroll(Offset(0f, 60f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(Mode.COMPACT, chrome.mode)
        top = true
        chrome.connection.onPostScroll(Offset.Zero, Offset(0f, 2f), NestedScrollSource.UserInput)
        assertEquals(Mode.EXPANDED, chrome.mode)
    }

    @Test fun flingAndProgrammaticMovementDoNotRevealTheHeader() {
        val chrome = BrowseChromeState({ false }, 1f)
        chrome.connection.onPostScroll(Offset(0f, -20f), Offset.Zero, NestedScrollSource.UserInput)
        chrome.connection.onPostScroll(Offset(0f, 500f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(Mode.HIDDEN, chrome.mode)
    }

    @Test fun newFolderResetShowsIntroductionAgain() {
        val chrome = BrowseChromeState({ false }, 1f)
        chrome.connection.onPostScroll(Offset(0f, -20f), Offset.Zero, NestedScrollSource.UserInput)
        chrome.reset()
        assertEquals(Mode.EXPANDED, chrome.mode)
    }
}
