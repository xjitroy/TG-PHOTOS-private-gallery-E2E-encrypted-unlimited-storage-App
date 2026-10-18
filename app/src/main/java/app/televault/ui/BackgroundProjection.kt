package app.televault.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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

/** Source progress may change while a search is reading encrypted records. Finish that read,
 * then consume the newest source snapshot; only user filters cancel an in-flight projection. */
@Composable
internal fun <S, I, T> rememberCoalescedProjection(scope: S, input: I, empty: T, project: suspend (I) -> T): BackgroundProjection<T> {
    val latest by rememberUpdatedState(input)
    val projector by rememberUpdatedState(project)
    var failed by remember(scope) { mutableStateOf(false) }
    var attempts by remember(scope) { mutableIntStateOf(0) }
    val snapshot by produceState<ProjectionSnapshot<S, T>?>(null, scope, attempts) {
        var previous: I? = null
        var first = true
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val next = latest
            if (first || previous != next) {
                first = false
                previous = next
                val result = guardedProjection<ProjectionSnapshot<S, T>?>(null) {
                    val output = withContext(Dispatchers.Default) { projector(next) }
                    ProjectionSnapshot(scope, next as Any, output)
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                failed = result == null
                if (result != null) value = result
            }
            kotlinx.coroutines.delay(2000)
        }
    }
    return BackgroundProjection(snapshot?.valueIn(scope) ?: empty, snapshot?.scope == scope || failed, failed) { attempts++ }
}
