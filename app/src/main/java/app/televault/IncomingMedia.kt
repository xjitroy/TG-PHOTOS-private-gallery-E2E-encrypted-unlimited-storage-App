package app.televault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.televault.data.LocalMedia
import app.televault.domain.ExternalMediaPolicy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class IncomingMedia(val id: String, val uris: List<Uri>, val backup: Boolean, val mime: String?) {
    companion object {
        @Suppress("DEPRECATION")
        fun read(intent: Intent, ownPackage: String): IncomingMedia? {
            val backup = intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
            if (!backup && intent.action != Intent.ACTION_VIEW) return null
            val uris = buildList {
                if (!backup) intent.data?.let(::add)
                if (backup) {
                    runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let(::add)
                    runCatching { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.take(ExternalMediaPolicy.MAX_FILES)?.let(::addAll)
                    intent.clipData?.let { clip -> repeat(minOf(clip.itemCount, ExternalMediaPolicy.MAX_FILES)) { clip.getItemAt(it).uri?.let(::add) } }
                }
            }.distinct().filter { it.toString().length <= 8192 && ExternalMediaPolicy.readable(it.scheme, it.authority, ownPackage) }.take(ExternalMediaPolicy.MAX_FILES)
            return if (uris.isEmpty()) null else IncomingMedia(UUID.randomUUID().toString(), uris, backup, intent.type)
        }
    }
    suspend fun resolve(context: Context): List<LocalMedia> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                var name = "Shared file"
                var size = 0L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0).orEmpty().take(512).ifBlank { name }
                        if (!cursor.isNull(1)) size = cursor.getLong(1).coerceAtLeast(0)
                    }
                }
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { if (size == 0L) size = it.length.coerceAtLeast(0) } ?: return@mapNotNull null
                val type = context.contentResolver.getType(uri) ?: mime?.takeUnless { '*' in it } ?: "application/octet-stream"
                LocalMedia(uri, name, type, size, System.currentTimeMillis(), 0, "Shared with TG Photos")
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
        }
    }
}
