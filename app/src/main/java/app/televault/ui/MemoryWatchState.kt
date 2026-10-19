package app.televault.ui

internal class MemoryWatchState(private val ids: Set<String>) {
    private val watched = mutableSetOf<String>()
    fun viewed(id: String): Boolean {
        if (id in ids) watched += id
        return ids.isNotEmpty() && watched.containsAll(ids)
    }
}

internal fun memoryReceipt(key: String, entries: List<Triple<String, Long, Long>>): String {
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        hash.update("${value.length}:".toByteArray())
        hash.update(value.toByteArray())
    }
    add(key)
    entries.forEach { (id, date, size) ->
        add(id)
        add(date.toString())
        add(size.toString())
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
