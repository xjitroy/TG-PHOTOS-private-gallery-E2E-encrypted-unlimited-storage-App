package app.televault.data

import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** Process-only ownership; tokens contain no credentials and never authorize access. */
internal class ReadCancellationRegistry {
    private val leases = ConcurrentHashMap<String, Lease>()
    inner class Lease internal constructor(val source: String, parent: Job?) : AutoCloseable {
        val token: String = UUID.randomUUID().toString()
        internal val job = SupervisorJob(parent)
        override fun close() {
            leases.remove(token, this)
            job.cancel()
        }
    }
    fun open(source: String, parent: Job? = null): Lease = Lease(source, parent).also { lease ->
        leases[lease.token] = lease
        lease.job.invokeOnCompletion { leases.remove(lease.token, lease) }
        if (!lease.job.isActive) throw FileNotFoundException("Media read was cancelled")
    }
    fun reader(token: String, source: String): CompletableJob {
        val lease = leases[token] ?: throw FileNotFoundException("Media read was cancelled")
        if (lease.source != source || !lease.job.isActive) throw FileNotFoundException("Media read was cancelled or changed")
        // Parent cancellation also reaches a child registered concurrently with close().
        return SupervisorJob(lease.job).also {
            if (!it.isActive) throw FileNotFoundException("Media read was cancelled")
        }
    }
    internal val size: Int get() = leases.size
}
