package app.televault.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Speculative work yields until the selected image has authenticated pixels (or an error). */
internal class PhotoLoadPriority {
    private data class Focus(val token: Long, val id: String)
    private val focus = MutableStateFlow<Focus?>(null)
    private var serial = 0L

    @Synchronized fun begin(id: String): Long = (++serial).also { focus.value = Focus(it, id) }

    @Synchronized fun ready(token: Long) {
        if (focus.value?.token == token) focus.value = null
    }

    @Synchronized fun clear() {
        serial++
        focus.value = null
    }
    suspend fun await(id: String) {
        focus.first { it == null || it.id == id }
    }
}
