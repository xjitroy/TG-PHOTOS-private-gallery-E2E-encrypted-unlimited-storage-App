package app.televault.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copies are read back and verified before the UI asks Android to remove the originals. */
object LocalFileActions {
    suspend fun rename(context: Context, item: LocalMedia, name: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 240 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() })
        val physical = item.physicalPath ?: item.uri.takeIf { it.scheme == "file" }?.path
        if (physical != null && DeviceFileAccess.granted(context)) {
            val source = java.io.File(physical).canonicalFile
            check(source.path.startsWith("/storage/") && !source.path.contains("/Android/data/") && !source.path.contains("/Android/obb/"))
            val target = java.io.File(source.parentFile, name).canonicalFile
            check(target.parentFile == source.parentFile && !target.exists()) { "That filename already exists." }
            check(source.renameTo(target)) { "This storage provider did not permit rename." }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(source.path, target.path), null, null)
        } else {
            check(android.provider.DocumentsContract.isDocumentUri(context, item.uri)) { "Choose this file through Android's folder picker to allow rename." }
            checkNotNull(android.provider.DocumentsContract.renameDocument(context.contentResolver, item.uri, name))
        }
    }

    /** Regular shared-storage files are seekable; retain the provider URI when access is unavailable. */
    suspend fun playbackSource(context: Context, item: LocalMedia): Uri = withContext(Dispatchers.IO) {
        if (item.trashed || !DeviceFileAccess.granted(context)) return@withContext item.uri
        val path = item.physicalPath ?: return@withContext item.uri
        val file = runCatching { java.io.File(path).canonicalFile }.getOrNull() ?: return@withContext item.uri
        if (file.path.startsWith("/storage/") && !file.path.contains("/Android/data/") && !file.path.contains("/Android/obb/") && file.isFile && file.canRead() && file.length() == item.size) Uri.fromFile(file) else item.uri
    }

    suspend fun createFolder(context: Context, tree: Uri, name: String): Uri = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 120 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 })
        val parent = checkNotNull(DocumentFile.fromTreeUri(context, tree))
        check(parent.canWrite()) { "Choose a writable parent folder." }
        check(parent.findFile(name) == null) { "A file or folder with this name already exists." }
        checkNotNull(parent.createDirectory(name)).uri
    }
    class PartialCopyException(val completedCount: Int, cause: Throwable) : Exception("Copy stopped; originals preserved", cause)
    suspend fun copyOrMove(context: Context, items: List<LocalMedia>, tree: Uri, folder: String, copy: Boolean): List<Uri> = withContext(Dispatchers.IO) {
        require(!folder.contains('/') && folder != "." && folder != "..")
        val parent = checkNotNull(DocumentFile.fromTreeUri(context, tree))
        val destination = if (folder.isEmpty()) parent else parent.findFile(folder)?.also { check(it.isDirectory) } ?: checkNotNull(parent.createDirectory(folder))
        val completed = mutableListOf<Uri>()
        for (item in items) {
            coroutineContext.ensureActive()
            val base = item.name.replace(Regex("[\\/\\\\\\u0000-\\u001f]"), "_").take(200).ifBlank { "File" }
            val name = if (destination.findFile(base) == null) {
                base
            } else {
                val stem = base.substringBeforeLast('.', base)
                val extension = base.substringAfterLast('.', "").takeIf { '.' in base }.orEmpty()
                "$stem (copy ${java.util.UUID.randomUUID().toString().take(8)})" + if (extension.isEmpty()) "" else ".$extension"
            }
            val output = try {
                checkNotNull(destination.createFile(item.mime, name))
            } catch (e: Exception) {
                throw PartialCopyException(completed.size, e)
            }
            check(DeviceFileAccess.identity(output.uri) != DeviceFileAccess.identity(item.uri)) { "Provider returned the original; refusing overwrite" }
            try {
                val original = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                context.contentResolver.openInputStream(item.uri)!!.use { input ->
                    context.contentResolver.openOutputStream(output.uri, "w")!!.use { sink ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            original.update(buffer, 0, count)
                            sink.write(buffer, 0, count)
                            bytes += count
                        }
                        buffer.fill(0)
                    }
                }
                val saved = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(output.uri)!!.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        saved.update(buffer, 0, count)
                    }
                    buffer.fill(0)
                }
                check(bytes == item.size && MessageDigest.isEqual(original.digest(), saved.digest()))
                completed += item.uri
            } catch (failure: Throwable) {
                runCatching { output.delete() }
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                throw PartialCopyException(completed.size, failure)
            }
        }
        if (copy) emptyList() else completed
    }
}
