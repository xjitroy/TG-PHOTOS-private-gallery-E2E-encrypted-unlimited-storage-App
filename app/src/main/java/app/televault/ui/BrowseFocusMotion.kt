package app.televault.ui

import kotlin.math.abs

/** Only the final visible distance is animated, even inside a very tall mosaic block. */
internal object BrowseFocusMotion {
    data class Travel(val jump: Float, val glide: Float, val durationMs: Int)
    fun travel(delta: Float, viewport: Float): Travel {
        if (!delta.isFinite() || !viewport.isFinite() || viewport <= 0) return Travel(0f, 0f, 0)
        val glide = delta.coerceIn(-viewport * .8f, viewport * .8f)
        val duration = if (abs(glide) <= 1f) 0 else (220 + 100 * abs(glide) / viewport).toInt()
        return Travel(delta - glide, glide, duration)
    }
}
