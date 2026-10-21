package app.televault.data

/** RAM-only retry position. A slow/bad early file cannot monopolize every bounded batch. */
internal class IntelligenceBatchCursor {
    private var session = ""
    private val last = mutableMapOf<Boolean, Pair<String, String>>()

    @Synchronized fun begin(scope: String) {
        if (session != scope) {
            session = scope
            last.clear()
        }
    }

    @Synchronized fun <T> order(advanced: Boolean, items: List<T>, identity: (T) -> Pair<String, String>): List<T> {
        val after = last[advanced] ?: return items
        val at = items.indexOfFirst { identity(it) == after }
        if (at < 0 || at == items.lastIndex) return items
        return items.drop(at + 1) + items.take(at + 1)
    }

    @Synchronized fun mark(advanced: Boolean, id: String, stamp: String) {
        last[advanced] = id to stamp
    }
}
