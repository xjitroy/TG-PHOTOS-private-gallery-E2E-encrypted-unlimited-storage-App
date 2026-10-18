package app.televault.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bound admitted UI mutations, share identical work, and cancel waiting work on lock. */
internal class BoundedOperations<K>(private val scope: CoroutineScope, private val full: () -> Unit, private val capacity: Int = 8, parallelism: Int = 2) {
    private val permits = Semaphore(parallelism)
    private val jobs = mutableMapOf<K, Job>()
    init {
        require(capacity >= parallelism && parallelism > 0)
    }
    fun submit(key: K, action: suspend () -> Unit): Job {
        val job = synchronized(this) {
            jobs[key]?.takeIf { !it.isCompleted }?.let { return it }
            if (jobs.size >= capacity) {
                null
            } else {
                scope.launch(start = CoroutineStart.LAZY) {
                    permits.withPermit { action() }
                }.also { task ->
                    jobs[key] = task
                    task.invokeOnCompletion { synchronized(this) { if (jobs[key] === task) jobs.remove(key) } }
                }
            }
        }
        if (job == null) {
            full()
            return Job().apply { cancel(CancellationException("Operation queue is full")) }
        }
        job.start()
        return job
    }
    fun cancel() {
        val active = synchronized(this) { jobs.values.toList().also { jobs.clear() } }
        active.forEach { it.cancel() }
    }
}
