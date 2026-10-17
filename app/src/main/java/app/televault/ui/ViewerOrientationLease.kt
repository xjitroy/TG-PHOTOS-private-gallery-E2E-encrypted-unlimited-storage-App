package app.televault.ui

/** One orientation owner for the whole viewer; outgoing pager cells never restore it. */
internal class ViewerOrientationLease(private val original: Int, private val portrait: Int, private val landscape: Int, private val read: () -> Int, private val apply: (Int) -> Unit) {
    private var selected: String? = null
    private var last: Int? = null
    private var closed = false
    fun select(identity: String?) {
        if (closed || selected == identity) return
        selected = identity
        set(portrait)
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
