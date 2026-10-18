package app.televault.memories

/** Encoded-row budget, including replacement accounting; never mutates on rejected admission. */
internal class IndexBudget(private val capacity: Long) {
    private val sizes = mutableMapOf<String, Int>()
    var used = 0L
        private set
    init {
        require(capacity > 0)
    }
    fun replace(id: String, bytes: Int): Boolean {
        require(bytes >= 0)
        val next = used - (sizes[id] ?: 0) + bytes
        if (next > capacity) return false
        sizes[id] = bytes
        used = next
        return true
    }
    fun remove(id: String) {
        used -= sizes.remove(id) ?: 0
    }
    fun clear() {
        sizes.clear()
        used = 0
    }
}
