package app.televault.data

import kotlinx.coroutines.*

/** One current request plus a short neighbor list; replaced queues never accumulate. */
internal class PreviewSequence<T>(private val scope: CoroutineScope, private val load: suspend (T, PreviewRequestOwner) -> Unit) : AutoCloseable {
    private var task: Job? = null
    private var reads: PreviewRequestOwner? = null
    fun request(items: List<T>) {
        close()
        val owner = PreviewRequestOwner().also { reads = it }
        task = scope.launch {
            try {
                for (item in items) {
                    ensureActive()
                    owner.ensureOpen()
                    try {
                        load(item, owner)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        /* A failed preview does not stop other files. */
                    } catch (_: OutOfMemoryError) {
                        break
                    }
                }
            } finally {
                owner.close()
            }
        }
    }
    override fun close() {
        reads?.close()
        reads = null
        task?.cancel()
        task = null
    }
}
