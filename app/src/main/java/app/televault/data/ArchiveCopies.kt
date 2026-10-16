package app.televault.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*

internal data class ArchiveSource(val uri: Uri, val name: String)

/** Intentional plaintext ZIP export to the user's chosen SAF destination; no staging files. */
internal object ArchiveCopies {
    suspend fun save(context: Context, sources: List<ArchiveSource>, destination: Uri) = withContext(Dispatchers.IO) {
        require(sources.size in 1..100)
        var safe = false
        try {
            val descriptor = context.contentResolver.openFileDescriptor(destination, "rw") ?: error("Destination unavailable")
            try {
                val output = Os.fstat(descriptor.fileDescriptor)
                sources.forEach { source ->
                    require(source.uri != destination)
                    context.contentResolver.openFileDescriptor(source.uri, "r")!!.use { input ->
                        val original = Os.fstat(input.fileDescriptor)
                        check(original.st_dev != output.st_dev || original.st_ino != output.st_ino) { "Choose a different destination." }
                    }
                }
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                safe = true
                Os.ftruncate(descriptor.fileDescriptor, 0)
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { outputStream ->
                    ZipOutputStream(outputStream).use { zip ->
                        val names = mutableSetOf<String>()
                        sources.forEachIndexed { index, source ->
                            var name = source.name.replace(Regex("[\\\\/\\u0000-\\u001f]"), "_").take(200).ifBlank { "File" }
                            if (name == "." || name == "..") name = "File_${index + 1}"
                            if (!names.add(name)) {
                                name = "${index + 1}_$name"
                                check(names.add(name))
                            }
                            zip.putNextEntry(ZipEntry(name))
                            context.contentResolver.openInputStream(source.uri)!!.use { input ->
                                val bytes = ByteArray(65536)
                                try {
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(bytes)
                                        if (count < 0) break
                                        zip.write(bytes, 0, count)
                                    }
                                } finally {
                                    bytes.fill(0)
                                }
                            }
                            zip.closeEntry()
                        }
                        zip.finish()
                        zip.flush()
                        outputStream.fd.sync()
                    }
                }
            } finally {
                descriptor.close()
            }
        } catch (e: Exception) {
            if (safe) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, destination) }
            throw e
        }
    }
}
