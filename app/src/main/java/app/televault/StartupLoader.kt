package app.televault

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent from Activity cancellation; retries share pending work and never reset stored data. */
internal class StartupLoader<T>(private val scope: CoroutineScope, private val create: suspend () -> T) {
    private val gate = Mutex()
    private var pending: Deferred<T>? = null
    suspend fun load(): T {
        val task = gate.withLock {
            pending?.takeUnless { it.isCancelled } ?: scope.async { create() }.also { pending = it }
        }
        return task.await()
    }
}
