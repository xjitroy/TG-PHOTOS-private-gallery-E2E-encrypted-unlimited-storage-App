package app.televault.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/** Resolve shared files to Android's own recoverable Trash. Never delete unsupported originals. */
internal object DeviceTrashUris {
    suspend fun unchanged(context: Context, item: LocalMedia): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (item.trashed) return@runCatching false
            if (item.uri.scheme == "file") {
                val file = File(checkNotNull(item.uri.path))
                file.isFile && file.canRead() && file.length() == item.size && file.lastModified() / 1000 == item.modified
            } else if (android.provider.DocumentsContract.isDocumentUri(context, item.uri)) {
                val file = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, item.uri)
                file != null && file.isFile && file.canRead() && file.length() == item.size && file.lastModified() / 1000 == item.modified
            } else {
                context.contentResolver.query(item.uri, arrayOf("_size", "date_modified"), null, null, null)?.use { cursor ->
                    cursor.moveToFirst() && cursor.getLong(0) == item.size && cursor.getLong(1) == item.modified
                } == true
            }
        }.getOrDefault(false)
    }
    suspend fun isTrashed(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext false
        runCatching {
            val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { it.moveToFirst() && it.getInt(0) == 1 } == true
        }.getOrDefault(false)
    }
    suspend fun resolve(context: Context, item: LocalMedia): Uri? = withContext(Dispatchers.IO) {
        if (item.uri.authority == "media") return@withContext item.uri
        if (!DeviceFileAccess.granted(context)) return@withContext null
        val identity = item.physicalPath ?: DeviceFileAccess.identity(item.uri)
        if (!identity.startsWith("/storage/")) return@withContext null
        val file = File(identity)
        if (!file.isFile || !file.canRead()) return@withContext null
        fun indexed(): Uri? = runCatching {
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf("_id"), "_data = ?", arrayOf(file.canonicalPath), null)?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(
                        when {
                            item.mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            item.mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            item.mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Files.getContentUri("external")
                        },
                        c.getLong(0)
                    )
                } else {
                    null
                }
            }
        }.getOrNull()
        indexed()?.let { return@withContext it }
        val scanned = withTimeoutOrNull(10000) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.canonicalPath), arrayOf(item.mime)) { _, uri -> if (continuation.isActive) continuation.resume(uri) }
            }
        }
        indexed() ?: scanned?.takeIf { it.authority == "media" }
    }
}
