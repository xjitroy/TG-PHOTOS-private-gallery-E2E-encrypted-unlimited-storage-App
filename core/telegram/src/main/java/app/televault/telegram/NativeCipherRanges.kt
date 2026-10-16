package app.televault.telegram

import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** TDLib's prefix counter alone does not validate an uploaded file's local path. */
internal object NativeCipherRanges {
    suspend fun read(offset: Long, count: Int, prefix: suspend () -> Long, localFile: suspend () -> File?, download: suspend () -> Unit, access: () -> Unit): ByteArray {
        require(offset >= 0 && count in 1..2 * 1024 * 1024 && offset <= Long.MAX_VALUE - count)
        fun readable(file: File?): Boolean = file != null && file.isFile && file.canRead() && file.length() >= offset + count
        access()
        coroutineContext.ensureActive()
        // Upload staging may already have been removed, while TDLib still reports a full prefix.
        // downloadFile performs TDLib's actual local-location check before starting a fresh range.
        if (prefix() < count || !readable(localFile())) download()
        return withTimeout(120_000) {
            var file: File?
            while (true) {
                access()
                coroutineContext.ensureActive()
                file = localFile()
                if (prefix() >= count && readable(file)) break
                delay(150)
            }
            val bytes = ByteArray(count)
            var returned = false
            try {
                RandomAccessFile(checkNotNull(file), "r").use {
                    it.seek(offset)
                    it.readFully(bytes)
                }
                access()
                coroutineContext.ensureActive()
                returned = true
                bytes
            } finally {
                if (!returned) bytes.fill(0)
            }
        }
    }
}
