package app.televault.data

import android.content.Context
import app.televault.crypto.CipherRangeCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class MediaStorageUsage(val limit: Long = 4L * 1024 * 1024 * 1024, val temporary: Long = 0, val native: Long = 0, val offline: Long = 0, val staging: Long = 0, val catalog: Long = 0, val other: Long = 0, val derived: Long = 0, val models: Long = 0)

/** Only disposable ciphertext is managed; never delete device originals, pins, keys or jobs. */
internal class MediaStorage(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("media-storage", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(MediaStorageUsage(limit = quota()))
    val usage = mutable.asStateFlow()
    private val directory = File(app.cacheDir, "encrypted-media")
    private val updates = Mutex()
    val cache: CipherRangeCache = synchronized(caches) {
        caches.getOrPut(app.noBackupFilesDir.absolutePath) {
            val legacy = File(app.noBackupFilesDir, "stream-cipher-cache")
            // Only this disposable ciphertext directory moves; no database, keys or pins.
            if (!directory.exists() && legacy.isDirectory) legacy.renameTo(directory)
            // Any leftover migration cleanup is deferred to IO work, not app construction.
            CipherRangeCache(directory, quota() - RESERVE, System::currentTimeMillis)
        }
    }
    private fun quota(): Long = prefs.getLong("temporary-limit", 4L * 1024 * 1024 * 1024).coerceIn(512L * 1024 * 1024, 8L * 1024 * 1024 * 1024)
    suspend fun setQuota(bytes: Long) = withContext(Dispatchers.IO) {
        require(bytes in setOf(512L, 1024L, 3072L, 4096L, 8192L).map { it * 1024 * 1024 })
        updates.withLock {
            check(prefs.edit().putLong("temporary-limit", bytes).commit())
            cache.setCapacity(bytes - RESERVE)
            refreshLocked()
        }
    }
    suspend fun refresh() = withContext(Dispatchers.IO) { updates.withLock { refreshLocked() } }
    suspend fun pruneTemporary() = withContext(Dispatchers.IO) {
        cache.prune()
        pruneLegacy()
    }
    private fun pruneLegacy() {
        val legacy = File(app.noBackupFilesDir, "stream-cipher-cache")
        if (!legacy.isDirectory) return
        val owned = Regex("(?:[0-9a-f]{64}(?:-photo)?\\.(?:bin|age)|range-.*\\.tmp)")
        legacy.listFiles().orEmpty().filter { it.isFile && owned.matches(it.name) }.forEach { it.delete() }
        legacy.delete() // succeeds only when empty, preserving unexpected files
    }
    private fun refreshLocked() {
        cache.prune()
        pruneLegacy()
        mutable.value = mediaStorageSnapshot(app.filesDir, app.noBackupFilesDir, app.cacheDir, quota())
    }
    companion object {
        // Reserve for the native transport's local ciphertext and bounded legacy upload cache.
        private const val RESERVE = 256L * 1024 * 1024
        private val caches = mutableMapOf<String, CipherRangeCache>()
    }
}
