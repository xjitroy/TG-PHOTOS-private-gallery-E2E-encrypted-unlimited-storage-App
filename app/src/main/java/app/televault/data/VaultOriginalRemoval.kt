package app.televault.data

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.security.MessageDigest
import kotlinx.coroutines.*

/** Never runs until a committed cloud Vault entry and the original's current hash agree. */
internal object VaultOriginalRemoval {
    data class Result(val consent: PendingIntent?, val retained: Int)
    suspend fun prepare(context: Context, vault: PrivateVaultRepository, sources: List<Pair<Uri, String>>): Result = withContext(Dispatchers.IO) {
        val media = mutableListOf<Uri>()
        var retained = 0
        for ((source, id) in sources.distinctBy { it.first }) {
            currentCoroutineContext().ensureActive()
            val epoch = vault.currentEpoch()
            val item = vault.item(id)
            check(item.cloud != null)
            try {
                val hash = item.hash.clone()
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                context.contentResolver.openInputStream(source)!!.use { input ->
                    val bytes = ByteArray(65536)
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(bytes)
                            if (n < 0) break
                            size += n
                            check(size <= item.size)
                            digest.update(bytes, 0, n)
                        }
                    } finally {
                        bytes.fill(0)
                    }
                }
                check(size == item.size && MessageDigest.isEqual(hash, digest.digest())) { "Original changed; keep it" }
                hash.fill(0)
                check(vault.valid(epoch))
                val mediaUri = if (source.authority == MediaStore.AUTHORITY && source.pathSegments.contains("picker")) {
                    // Picker IDs can also identify remote assets. Only a readable, hash-matching
                    // local MediaStore row may become a deletion candidate.
                    val number = source.lastPathSegment?.toLongOrNull()
                    val base = if (item.mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val candidate = number?.let { android.content.ContentUris.withAppendedId(base, it) }
                    if (candidate != null) {
                        val localDigest = MessageDigest.getInstance("SHA-256")
                        var localSize = 0L
                        context.contentResolver.openInputStream(candidate)!!.use { input ->
                            val buffer = ByteArray(65536)
                            try {
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    check(vault.valid(epoch))
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    localSize += n
                                    check(localSize <= item.size)
                                    localDigest.update(buffer, 0, n)
                                }
                            } finally {
                                buffer.fill(0)
                            }
                        }
                        check(localSize == item.size && MessageDigest.isEqual(item.hash, localDigest.digest()))
                    }
                    candidate
                } else if (source.authority == MediaStore.AUTHORITY) {
                    source
                } else if (Build.VERSION.SDK_INT >= 29 && source.authority == "com.android.providers.media.documents") {
                    MediaStore.getMediaUri(context, source)
                } else {
                    null
                }
                when {
                    mediaUri != null && Build.VERSION.SDK_INT >= 30 -> media.add(mediaUri)
                    DocumentsContract.isDocumentUri(context, source) -> check(DocumentsContract.deleteDocument(context.contentResolver, source))
                    else -> retained++ // Photo Picker/other-provider/internal-cloud URIs may not grant deletion.
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                retained++
            }
        }
        Result(if (media.isEmpty() || Build.VERSION.SDK_INT < 30) null else MediaStore.createDeleteRequest(context.contentResolver, media), retained)
    }
}
