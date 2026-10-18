package app.televault.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A scope includes the access epoch and every visible filter, never just the source list. */
internal data class ProjectionSnapshot<S, T>(val scope: S, val request: Any, val value: T) {
    fun valueIn(currentScope: S): T? = value.takeIf { scope == currentScope }
}

internal data class BackgroundProjection<T>(val value: T, val ready: Boolean, val failed: Boolean = false, val retry: () -> Unit = {})

/** Keep painted rows during an index update, but never carry rows across folders or access epochs. */
@Composable
internal fun <S, I, T> rememberBackgroundProjection(scope: S, input: I, empty: T, project: suspend (I) -> T): BackgroundProjection<T> {
    var attempts by remember(scope) { mutableIntStateOf(0) }
    var failedRequest by remember { mutableStateOf<Any?>(null) }
    val request = remember(scope, input, attempts) { Any() }
    val snapshot by produceState<ProjectionSnapshot<S, T>?>(null, request) {
        val computed = guardedProjection<ProjectionSnapshot<S, T>?>(null) {
            val result = withContext(Dispatchers.Default) { project(input).also { coroutineContext.ensureActive() } }
            ProjectionSnapshot(scope, request, result)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (computed != null) {
            value = computed
        } else {
            failedRequest = request
        }
    }
    val failed = failedRequest === request
    return BackgroundProjection(snapshot?.valueIn(scope) ?: empty, snapshot?.request === request || failed, failed) { attempts++ }
}

/** A stale/locked index must clear the projection, never crash the composition. */
internal suspend fun <T> guardedProjection(empty: T, project: suspend () -> T): T = try {
    project()
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (_: Exception) {
    empty
} catch (_: OutOfMemoryError) {
    empty
}
