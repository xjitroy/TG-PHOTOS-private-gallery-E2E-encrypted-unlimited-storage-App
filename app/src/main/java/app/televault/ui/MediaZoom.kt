package app.televault.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import app.televault.domain.ZoomTransform

/** Snapshot reads live only in the render layer, not in the whole player/gallery composition. */
@Stable
internal class MediaZoomState(maximum: Float = 6f) {
    private val geometry = ZoomTransform(maximum)
    var scale by mutableFloatStateOf(1f)
        private set
    var x by mutableFloatStateOf(0f)
        private set
    var y by mutableFloatStateOf(0f)
        private set
    private fun publish() {
        scale = geometry.scale()
        x = geometry.x()
        y = geometry.y()
    }
    fun viewport(width: Int, height: Int) {
        geometry.viewport(width.toFloat(), height.toFloat())
        publish()
    }
    fun transform(factor: Float, focus: Offset, pan: Offset) {
        geometry.transform(factor, focus.x, focus.y, pan.x, pan.y)
        publish()
    }
    fun reset() {
        geometry.reset()
        publish()
    }
}

internal fun Modifier.mediaZoomLayer(state: MediaZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.x
    translationY = state.y
}

/** Consume transforms before tap/seek/swipe recognizers; a two-finger gesture owns its tail. */
internal fun Modifier.mediaZoomInput(state: MediaZoomState): Modifier = onSizeChanged { state.viewport(it.width, it.height) }.pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var transformed = false
        var moving = false
        var distance = Offset.Zero
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val fingers = event.changes.count { it.pressed && it.previousPressed }
            val pan = event.calculatePan()
            if (fingers >= 2) {
                transformed = true
                state.transform(event.calculateZoom(), event.calculateCentroid(useCurrent = false), pan)
                event.changes.forEach { it.consume() }
            } else if (transformed) {
                if (fingers > 0) state.transform(1f, event.calculateCentroid(useCurrent = false), pan)
                event.changes.forEach { it.consume() }
            } else if (state.scale > 1.001f) {
                distance += pan
                if (distance.getDistance() > viewConfiguration.touchSlop)moving = true
                if (moving) {
                    state.transform(1f, event.calculateCentroid(useCurrent = false), pan)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
