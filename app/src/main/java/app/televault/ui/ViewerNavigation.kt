package app.televault.ui

import kotlinx.coroutines.*

/** Main-thread owner: a cancelled/failed old move cannot settle a newer gesture. */
internal class ViewerNavigation(private val scope: CoroutineScope, private val move: suspend (Int, Boolean) -> Unit, private val failed: (Exception) -> Unit) {
    private var generation = 0L
    private var job: Job? = null
    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }
    fun select(index: Int, animate: Boolean, settled: () -> Unit) {
        cancel()
        val selected = generation
        // Dispatch normally: never re-enter pager measurement from a gesture/snapshot collector.
        job = scope.launch {
            try {
                move(index, animate)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (selected == generation) failed(error)
            } finally {
                if (selected == generation) settled()
            }
        }
    }
}
