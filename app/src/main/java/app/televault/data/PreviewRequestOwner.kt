package app.televault.data

import kotlinx.coroutines.CancellationException

/** Cancel source-bound reads immediately; native cleanup remains on the decoder's IO thread. */
internal class PreviewRequestOwner : AutoCloseable {
    private var closed = false
    private val reads = mutableListOf<AutoCloseable>()

    @Synchronized fun ensureOpen() {
        if (closed) throw CancellationException("Preview superseded")
    }
    fun <T : AutoCloseable> register(read: T): T {
        synchronized(this) {
            if (!closed) {
                reads += read
                return read
            }
        }
        read.close()
        throw CancellationException("Preview superseded")
    }

    @Synchronized fun publish(action: () -> Unit) {
        ensureOpen()
        action()
    }
    override fun close() {
        val old = synchronized(this) {
            if (closed) return
            closed = true
            reads.toList().also { reads.clear() }
        }
        old.forEach { runCatching { it.close() } }
    }
}
