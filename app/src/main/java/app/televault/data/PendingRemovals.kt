package app.televault.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation only. Repository records remain authoritative; failure reveals unchanged files. */
internal class PendingRemovals {
    private var sequence = 0L
    private val requests = mutableMapOf<Long, Set<String>>()
    private val references = mutableMapOf<String, Int>()
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    val ids = hidden.asStateFlow()

    @Synchronized fun begin(ids: Set<String>): Long {
        val token = ++sequence
        val selected = ids.toSet()
        requests[token] = selected
        selected.forEach { references[it] = (references[it] ?: 0) + 1 }
        publish()
        return token
    }

    @Synchronized fun end(token: Long) {
        val selected = requests.remove(token) ?: return
        selected.forEach { id ->
            val remaining = checkNotNull(references[id]) - 1
            if (remaining == 0) references.remove(id) else references[id] = remaining
        }
        publish()
    }

    @Synchronized fun clear() {
        requests.clear()
        references.clear()
        publish()
    }
    private fun publish() {
        hidden.value = references.keys.toSet()
    }
    suspend fun <T> run(ids: Set<String>, action: suspend () -> T): T {
        val token = begin(ids)
        try {
            return action()
        } finally {
            end(token)
        }
    }
    companion object {
        private val active = setOf("WAITING", "RETRYING", "INTERRUPTED", "WAITING_TELEGRAM", "WAITING_UNLOCK", "WAITING_NETWORK", "WAITING_WIFI")
        fun queuedPurge(id: String, phase: String): String? = id.takeIf { it.startsWith("purge:") && it.length > 6 && phase in active }?.removePrefix("purge:")
    }
}
