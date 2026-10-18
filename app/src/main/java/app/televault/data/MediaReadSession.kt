package app.televault.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*

/** A viewer owns its network reads; closing it never waits for native/descriptor cleanup. */
internal class MediaReadSession private constructor(val uri: Uri, private val lease: ReadCancellationRegistry.Lease?, private val parent: Job? = null) : AutoCloseable {
    private val signal = CancellationSignal()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    fun descriptor(context: Context): ParcelFileDescriptor {
        if (closed.get()) throw java.io.FileNotFoundException("Media read was cancelled")
        return checkNotNull(context.contentResolver.openFileDescriptor(uri, "r", signal))
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lease?.close()
        parent?.cancel()
        // A provider's cancellation transport can perform Binder IPC.
        dispose { signal.cancel() }
    }
    companion object {
        private const val PARAM = "tg_read_owner"
        private val registry = ReadCancellationRegistry()
        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun dispose(action: () -> Unit) {
            cleanup.launch { runCatching(action) }
        }
        private fun source(uri: Uri): Uri = uri.buildUpon().clearQuery().apply {
            uri.queryParameterNames.filter { it != PARAM }.sorted().forEach { name ->
                uri.getQueryParameters(name).forEach { value -> appendQueryParameter(name, value) }
            }
        }.build()
        fun open(context: Context, uri: Uri): MediaReadSession {
            if (uri.scheme != "content" || uri.authority != "${context.packageName}.private-media" || uri.pathSegments.firstOrNull() !in setOf("media", "private-vault")) return MediaReadSession(uri, null)
            val base = source(uri)
            val parent = try {
                uri.getQueryParameter(PARAM)?.let { registry.reader(it, base.toString()) }
            } catch (_: java.io.FileNotFoundException) {
                // The provider will reject this revoked URI on its I/O thread.
                return MediaReadSession(uri, null)
            }
            try {
                val lease = registry.open(base.toString(), parent)
                return MediaReadSession(base.buildUpon().appendQueryParameter(PARAM, lease.token).build(), lease, parent)
            } catch (failure: Throwable) {
                parent?.cancel()
                if (failure is java.io.FileNotFoundException) return MediaReadSession(uri, null)
                throw failure
            }
        }
        fun readerJob(uri: Uri): CompletableJob {
            val token = uri.getQueryParameter(PARAM) ?: return SupervisorJob()
            return registry.reader(token, source(uri).toString())
        }
    }
}
