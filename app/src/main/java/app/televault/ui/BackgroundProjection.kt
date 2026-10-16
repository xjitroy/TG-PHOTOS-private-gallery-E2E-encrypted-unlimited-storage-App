package app.televault.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A scope includes the access epoch and every visible filter, never just the source list. */
internal data class ProjectionSnapshot<S, T>(val scope: S, val request: Any, val value: T) {
    fun valueIn(currentScope: S): T? = value.takeIf { scope == currentScope }
}

internal data class BackgroundProjection<T>(val value: T, val ready: Boolean)

/** Keep painted rows during an index update, but never carry rows across folders or access epochs. */
@Composable
internal fun <S, I, T> rememberBackgroundProjection(scope: S, input: I, empty: T, project: suspend (I) -> T): BackgroundProjection<T> {
    val request = remember(scope, input) { Any() }
    val snapshot by produceState<ProjectionSnapshot<S, T>?>(null, request) {
        val computed = withContext(Dispatchers.Default) {
            project(input).also { coroutineContext.ensureActive() }
        }
        value = ProjectionSnapshot(scope, request, computed)
    }
    return BackgroundProjection(snapshot?.valueIn(scope) ?: empty, snapshot?.request === request)
}
