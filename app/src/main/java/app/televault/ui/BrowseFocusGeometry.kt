package app.televault.ui

internal object BrowseFocusGeometry {
    fun delta(itemTop: Float, itemBottom: Float, viewportTop: Float, viewportBottom: Float): Float? {
        if (listOf(itemTop, itemBottom, viewportTop, viewportBottom).any { !it.isFinite() } || itemBottom <= itemTop || viewportBottom <= viewportTop) return null
        return (itemTop + (itemBottom - itemTop) / 2) - (viewportTop + (viewportBottom - viewportTop) / 2)
    }
}
