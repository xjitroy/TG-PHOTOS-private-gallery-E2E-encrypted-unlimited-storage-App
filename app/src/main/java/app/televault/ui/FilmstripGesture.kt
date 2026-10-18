package app.televault.ui

import kotlin.math.roundToInt

/** Transient preview state is deliberately separate from committed pager/navigation state. */
internal class FilmstripGesture(private val count: Int, private val positionChanged: (Float) -> Unit, private val preview: (Int) -> Unit, private val started: () -> Unit, private val committed: (Int) -> Unit) {
    var position = 0f
        private set
    private var active = false
    fun begin(position: Float) {
        active = true
        started()
        update(position)
    }
    fun update(value: Float) {
        if (!active || count <= 0 || !value.isFinite()) return
        position = value.coerceIn(0f, (count - 1).toFloat())
        positionChanged(position)
        preview(position.roundToInt())
    }
    fun drag(delta: Float, stride: Float, rtl: Boolean) {
        update(ViewerFilmstripGeometry.drag(position, delta, stride, count, rtl))
    }
    fun finish(value: Float) {
        if (!active || count <= 0) return
        update(value)
        active = false
        committed(position.roundToInt())
    }
    fun cancel() {
        active = false
    }
}
