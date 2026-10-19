package app.televault.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Speculative work yields until the selected image has authenticated pixels (or an error). */
internal class PhotoLoadPriority {
    private data class Focus(val token: Long, val id: String)
    private val focus = MutableStateFlow<Focus?>(null)
    val selected = focus.map { it?.id }.distinctUntilChanged()
    private var serial = 0L

    @Synchronized fun begin(id: String): Long = (++serial).also { focus.value = Focus(it, id) }

    @Synchronized fun ready(token: Long) {
        if (focus.value?.token == token) focus.value = null
    }

    @Synchronized fun clear() {
        serial++
        focus.value = null
    }
    fun permits(id: String) = focus.value.let { it == null || it.id == id }
    suspend fun await(id: String) {
        focus.first { it == null || it.id == id }
    }
}
