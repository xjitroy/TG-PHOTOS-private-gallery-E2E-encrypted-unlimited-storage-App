package app.televault.data

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

/** One read-only pass per owned tree; links cannot escape into device originals or loop. */
internal fun mediaStorageSnapshot(files: File, noBackup: File, cache: File, limit: Long): MediaStorageUsage {
    var temporary = 0L
    var native = 0L
    var offline = 0L
    var staging = 0L
    var catalog = 0L
    var derived = 0L
    var models = 0L
    var other = storageTreeBytes(files)
    noBackup.listFiles().orEmpty().forEach { child ->
        when {
            child.name in setOf("people", "ocr-index", "intelligence-private") -> derived = storageSum(derived, storageTreeBytes(child))
            child.name == "ocr-models" -> models = storageSum(models, storageTreeBytes(child))
            child.name in setOf("stream-cipher-cache", "photo-cipher-cache") -> temporary = storageSum(temporary, storageTreeBytes(child))
            child.name == "offline-cipher" -> offline = storageSum(offline, storageTreeBytes(child))
            child.name == "drive-cache" -> staging = storageSum(staging, storageTreeBytes(child))
            child.name.startsWith("drive-cache.sqlite") -> catalog = storageSum(catalog, storageTreeBytes(child))
            child.name == "tdlib" && !Files.isSymbolicLink(child.toPath()) && child.isDirectory -> child.listFiles().orEmpty().forEach { transport ->
                when (transport.name) {
                    "files" -> native = storageSum(native, storageTreeBytes(transport))
                    "database" -> catalog = storageSum(catalog, storageTreeBytes(transport))
                    else -> other = storageSum(other, storageTreeBytes(transport))
                }
            }
            else -> other = storageSum(other, storageTreeBytes(child))
        }
    }
    cache.listFiles().orEmpty().forEach { child ->
        if (child.name == "encrypted-media") {
            temporary = storageSum(temporary, storageTreeBytes(child))
        } else {
            staging = storageSum(staging, storageTreeBytes(child))
        }
    }
    return MediaStorageUsage(limit, temporary, native, offline, staging, catalog, other, derived, models)
}

private fun storageSum(first: Long, second: Long): Long = if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

internal fun storageTreeBytes(root: File): Long {
    var total = 0L
    val pending = ArrayDeque<File>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val file = pending.removeLast()
        if (Files.isSymbolicLink(file.toPath())) continue
        if (file.isFile) {
            total = storageSum(total, file.length().coerceAtLeast(0))
        } else {
            file.listFiles().orEmpty().forEach { pending.add(it) }
        }
    }
    return total
}
