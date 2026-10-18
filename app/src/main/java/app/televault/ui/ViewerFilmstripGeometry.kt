package app.televault.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class FilmstripItem(val index: Int, val offset: Int, val size: Int)
internal data class FilmstripScrollTarget(val index: Int, val offset: Int)

/** LazyRow offsets include before-content padding; use its actual viewport, not screen width. */
internal object ViewerFilmstripGeometry {
    fun nearest(items: List<FilmstripItem>, viewportStart: Int, viewportEnd: Int, count: Int): Int? {
        if (count <= 0 || viewportEnd <= viewportStart) return null
        val center = viewportStart / 2f + viewportEnd / 2f
        return items.filter { it.index in 0 until count && it.size > 0 }
            .minByOrNull { abs(it.offset + it.size / 2f - center) }?.index
    }

    /** Equal edge padding makes scrollToItem(index, 0) center even the first/last thumbnail. */
    fun target(position: Float, count: Int, stride: Float): FilmstripScrollTarget? {
        if (count <= 0 || !position.isFinite() || !stride.isFinite() || stride <= 0f) return null
        val bounded = position.coerceIn(0f, (count - 1).toFloat())
        val index = floor(bounded).toInt()
        return FilmstripScrollTarget(index, ((bounded - index) * stride).roundToInt())
    }
}
