package app.televault.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Base64
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import app.televault.domain.BackedCopyVerifier
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Private RAM receipts only; no original is modified, cached on disk or trusted by filename. */
internal class DeviceCopyResolver(private val context: Context) {
    private val receipts = LruCache<String, Pair<Long, String>>(128)
    private val reads = Semaphore(2)
    private fun stamp(item: LocalMedia): String? = runCatching {
        if (item.uri.scheme == "file") {
            val file = File(checkNotNull(item.uri.path))
            if (!file.isFile || !file.canRead() || file.length() != item.size) return@runCatching null
            return@runCatching "${file.canonicalPath}|${file.length()}|${file.lastModified()}"
        }
        if (DocumentsContract.isDocumentUri(context, item.uri)) {
            val file = DocumentFile.fromSingleUri(context, item.uri) ?: return@runCatching null
            if (!file.isFile || !file.canRead() || file.length() != item.size) return@runCatching null
            return@runCatching "${item.uri}|${file.length()}|${file.lastModified()}"
        }
        val columns = if (Build.VERSION.SDK_INT >= 30) arrayOf("_size", "date_modified", "is_trashed") else arrayOf("_size", "date_modified")
        context.contentResolver.query(item.uri, columns, null, null, null)?.use {
            if (!it.moveToFirst() || it.getLong(0) != item.size || columns.size > 2 && it.getInt(2) != 0) null else "${item.uri}|${it.getLong(0)}|${it.getLong(1)}"
        }
    }.getOrNull()
    suspend fun verifiedHash(item: LocalMedia, fresh: Boolean): String? = withContext(Dispatchers.IO) {
        if (item.trashed) return@withContext null
        reads.withPermit {
            val before = stamp(item) ?: return@withPermit null
            if (!fresh) receipts.get(before)?.takeIf { SystemClock.elapsedRealtime() - it.first in 0 until 86400000L }?.let { return@withPermit it.second }
            try {
                val scope = coroutineContext
                val hash = context.contentResolver.openInputStream(item.uri)?.use { input -> BackedCopyVerifier.digest(input, item.size) { scope.ensureActive() } } ?: return@withPermit null
                if (stamp(item) != before) return@withPermit null
                val text = Base64.encodeToString(hash, Base64.NO_WRAP)
                if ((before.substringAfterLast("|").toLongOrNull() ?: 0) > 0) receipts.put(before, SystemClock.elapsedRealtime() to text)
                text
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    suspend fun preferred(file: DriveFile, items: List<LocalMedia>, links: Map<String, String>): LocalMedia? {
        val candidates = items.filter { !it.trashed && it.size == file.size && (it.uri.toString() == file.sourceUri || it.fingerprint == file.sourceToken || links[it.fingerprint] == file.id || it.name.equals(file.name, ignoreCase = true)) }
            .sortedBy { if (links[it.fingerprint] == file.id || it.fingerprint == file.sourceToken) 0 else 1 }
        if (file.trashedAt != 0L) return null
        for (item in candidates) if (verifiedHash(item, false) == file.hash) return item
        return null
    }
    fun clear() = receipts.evictAll()
}
