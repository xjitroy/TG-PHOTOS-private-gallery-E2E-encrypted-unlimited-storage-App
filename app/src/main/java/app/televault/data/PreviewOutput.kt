package app.televault.data

/** Thread-confined decoded output: once offered to a shared cache, only that cache/GC owns it. */
internal class PreviewOutput<T>(value: T, private val discard: (T) -> Unit) : AutoCloseable {
    private var value: T? = value
    fun publish(accept: (T) -> Boolean) {
        val ready = value ?: return
        // A cache can retain pixels and then throw while notifying/trimming under memory pressure.
        // Relinquish first so exception cleanup cannot recycle a renderer-owned bitmap.
        value = null
        if (!accept(ready)) discard(ready)
    }
    override fun close() {
        val ready = value ?: return
        value = null
        discard(ready)
    }
}
