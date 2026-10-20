package app.televault.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

internal fun viewerChromeHideDelay(video: Boolean, vertical: Boolean): Long = if (vertical) {
    3000L
} else if (video) {
    4000L
} else {
    5000L
}

/** One owner for filmstrip and playback chrome; neighbouring prepared players cannot reveal it. */
internal class ViewerChromeState(initialId: String, initiallyTapOnly: Boolean) {
    private var selectedId = initialId
    var tapOnly by mutableStateOf(initiallyTapOnly)
        private set
    var visible by mutableStateOf(!initiallyTapOnly)
        private set
    var held by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun select(id: String, onlyOnTap: Boolean) {
        if (selectedId == id && tapOnly == onlyOnTap) return
        val modeChanged = tapOnly != onlyOnTap
        selectedId = id
        tapOnly = onlyOnTap
        // Browsing must not undo a user's hide (or a completed auto-hide). Shorts still
        // starts each new reel hidden; an active filmstrip hold keeps controls usable.
        if (!held && (onlyOnTap || modeChanged)) visible = !onlyOnTap
        revision++
    }
    fun show(id: String) = setVisible(id, true)
    fun toggle(id: String) = setVisible(id, !visible)
    fun setVisible(id: String, show: Boolean) {
        if (id != selectedId) return
        visible = show
        revision++
    }
    fun interact(id: String) {
        if (id == selectedId) revision++
    }
    fun hold(pressed: Boolean) {
        if (held == pressed) return
        held = pressed
        revision++
    }
    fun expire(expectedRevision: Int) {
        if (!held && revision == expectedRevision) visible = false
    }
}

/** Observe, never consume: stationary slider/strip holds also suspend auto-hide. */
internal fun Modifier.holdViewerChrome(chrome: ViewerChromeState) = pointerInput(chrome) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                chrome.hold(event.changes.any { it.pressed })
            }
        }
    } finally {
        chrome.hold(false)
    }
}
