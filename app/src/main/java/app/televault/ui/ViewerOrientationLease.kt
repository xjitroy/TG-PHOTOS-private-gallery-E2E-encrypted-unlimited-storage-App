package app.televault.ui

/** One orientation owner for the whole viewer; outgoing pager cells never restore it. */
internal class ViewerOrientationLease(private val original: Int, private val portrait: Int, private val landscape: Int, private val read: () -> Int, private val apply: (Int) -> Unit) {
    private var selected: String? = null
    private var last: Int? = null
    private var closed = false
    private var suspended = false
    fun suspendSelection() {
        if (closed) return
        selected = null
        suspended = true
    }
    fun select(identity: String?, wide: Boolean = false) {
        if (closed || selected == identity && !suspended) return
        selected = identity
        suspended = false
        set(if (identity != null && wide) landscape else portrait)
    }
    fun request(identity: String, wide: Boolean, pip: Boolean) {
        if (closed || identity != selected) return
        set(
            if (pip) {
                original
            } else if (wide) {
                landscape
            } else {
                portrait
            }
        )
    }
    private fun set(value: Int) {
        if (read() != value) apply(value)
        last = value
    }
    fun close() {
        if (closed) return
        closed = true
        if (last != null && read() == last) apply(original)
        selected = null
    }
}
