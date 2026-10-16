package app.televault.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Read-only shared-storage scan. Never visits private app storage or enables upload. */
internal object DeviceFileAccess {
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun identity(uri: Uri): String {
        if (uri.scheme == "file") return runCatching { File(uri.path.orEmpty()).canonicalPath }.getOrDefault(uri.toString())
        if (uri.authority == "com.android.externalstorage.documents") {
            val id = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (id != null && id.startsWith("primary:")) return runCatching { File(Environment.getExternalStorageDirectory(), id.substringAfter(':')).canonicalPath }.getOrDefault(uri.toString())
        }
        return uri.toString()
    }

    suspend fun scan(context: Context, indexed: Set<String>, progress: (Int) -> Unit, partial: suspend (List<LocalMedia>) -> Unit = {}): List<LocalMedia> {
        val roots = buildSet {
            add(Environment.getExternalStorageDirectory())
            context.getExternalFilesDirs(null).filterNotNull().forEach { file ->
                val split = file.absolutePath.indexOf("/Android/data/")
                if (split > 0) add(File(file.absolutePath.substring(0, split)))
            }
        }
        val result = ArrayList<LocalMedia>()
        val visited = HashSet<String>()
        val files = HashSet(indexed)
        var lastPublish = android.os.SystemClock.elapsedRealtime()
        for (root in roots) {
            val base = runCatching { root.canonicalPath }.getOrNull() ?: continue
            val queue = java.util.ArrayDeque<Pair<File, Boolean>>()
            queue.add(root to false)
            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                val (folder, inheritedHidden) = queue.removeFirst()
                val galleryHidden = inheritedHidden || File(folder, ".nomedia").isFile
                val path = runCatching { folder.canonicalPath }.getOrNull() ?: continue
                if (path != base && !path.startsWith("$base/")) continue
                val relative = path.removePrefix(base).trimStart('/')
                if (!app.televault.domain.DeviceFileVisibility.visible(relative)) continue
                if (relative == "Android/data" || relative.startsWith("Android/data/") || relative == "Android/obb" || relative.startsWith("Android/obb/")) continue
                if (!visited.add(path)) continue
                val children = folder.listFiles()
                if (children == null && relative.isEmpty()) throw java.io.IOException("Shared-storage root unavailable")
                for (file in children.orEmpty()) {
                    coroutineContext.ensureActive()
                    val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
                    if (!canonical.startsWith("$base/")) continue
                    val target = canonical.removePrefix("$base/")
                    if (!app.televault.domain.DeviceFileVisibility.visible(target)) continue
                    if (target == "Android/data" || target.startsWith("Android/data/") || target == "Android/obb" || target.startsWith("Android/obb/")) continue
                    if (file.name.startsWith(".trashed-") || file.name.startsWith(".pending-")) continue
                    if (file.isDirectory) {
                        queue.add(file to galleryHidden)
                    } else if (file.isFile && file.canRead() && files.add(canonical)) {
                        val mime = app.televault.domain.MediaCategories.videoMime(file.name, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream")
                        val modified = file.lastModified()
                        val dimensions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        if (mime.startsWith("image/")) runCatching { android.graphics.BitmapFactory.decodeFile(canonical, dimensions) }
                        result += LocalMedia(Uri.fromFile(file), file.name, mime, file.length(), modified, modified / 1000, relative.ifBlank { root.name }, physicalPath = canonical, width = dimensions.outWidth.coerceAtLeast(0), height = dimensions.outHeight.coerceAtLeast(0), galleryHidden = galleryHidden)
                        if (result.size % 100 == 0) {
                            progress(result.size)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastPublish >= 750) {
                                partial(result.toList())
                                lastPublish = now
                            }
                        }
                    }
                }
            }
        }
        progress(result.size)
        return result
    }
}
