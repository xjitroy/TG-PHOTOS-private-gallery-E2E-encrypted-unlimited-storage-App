package app.televault.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LocalMedia(val uri: Uri, val name: String, val mime: String, val size: Long, val date: Long, val modified: Long, val folder: String, val trashed: Boolean = false, val physicalPath: String? = null, val width: Int = 0, val height: Int = 0, val addedAt: Long = 0, val galleryHidden: Boolean = false, val durationMs: Long = 0) {
    val mediaCategories: Int = app.televault.domain.MediaCategories.classify(name, folder, mime, false, durationMs)
    val fingerprint: String get() = "$uri|$size|$modified"
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class LocalMediaRepository(private val context: Context) {
    private val thumbnails = object : android.util.LruCache<String, Bitmap>(MediaMemory.budget(context, 12, 3)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val documentRoots = MutableStateFlow<List<Uri>>(emptyList())
    internal fun setDocumentRoots(roots: List<Uri>) {
        documentRoots.value = roots.distinct()
    }
    val scanningDocuments = MutableStateFlow(false)
    val scannedFiles = MutableStateFlow(0)
    val indexReady = MutableStateFlow(false)
    val scanFailed = MutableStateFlow(false)
    val accessRevision = MutableStateFlow(0L)
    private var durationJob: kotlinx.coroutines.Job? = null
    private val durationReceipts = object : android.util.LruCache<String, Long>(8192) {}
    private val durationRevision = java.util.concurrent.atomic.AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val cache = LocalIndexCache(context)
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var permissionStamp = ""
    private val latestIndex = MutableStateFlow<List<LocalMedia>>(emptyList())
    val changes = latestIndex.asStateFlow()
    private val observedFolders = MutableStateFlow<List<String>>(emptyList())
    private val storageEvents = combine(documentRoots, observedFolders) { roots, folders -> roots to folders }.flatMapLatest { (roots, folders) ->
        callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            val nativeObservers = folders.mapNotNull { path ->
                runCatching {
                    object : android.os.FileObserver(path, CREATE or CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE or DELETE_SELF) {
                        override fun onEvent(event: Int, path: String?) {
                            trySend(Unit)
                        }
                    }.also { it.startWatching() }
                }.getOrNull()
            }
            val watched = listOf(MediaStore.Files.getContentUri("external")) + roots
            watched.forEach { uri -> runCatching { context.contentResolver.registerContentObserver(uri, true, observer) } }
            // Updating discovered watchers must not request another scan or cancel its result.
            awaitClose {
                nativeObservers.forEach { it.stopWatching() }
                context.contentResolver.unregisterContentObserver(observer)
            }
        }
    }
    init {
        scope.launch {
            latestIndex.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                val restoredGeneration = generation.get()
                val stamp = accessStamp()
                if (changes.value.isEmpty()) cache.read(stamp)?.let { publish(it, restoredGeneration, stamp) }
                val rootsChanged = documentRoots.map { Unit }
                collectDeviceIndexUpdates(merge(storageEvents, refreshes, rootsChanged)) {
                    durationJob?.cancel()
                    durationRevision.incrementAndGet()
                    val epoch = generation.get()
                    val access = accessStamp()
                    scanningDocuments.value = true
                    scanFailed.value = false
                    try {
                        // Complete one scan. Changes arriving during it request one follow-up scan.
                        val items = scanMutex.withLock { scan(true) { publish(it, epoch, access) } }
                        publish(items, epoch, access)
                        if (!scanFailed.value) persist(items, epoch, access)
                        readMissingDurations(items, epoch, access)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        scanFailed.value = true
                    } finally {
                        scanningDocuments.value = false
                    }
                }
            }
        }
    }
    private fun readMissingDurations(items: List<LocalMedia>, epoch: Long, access: String) {
        durationJob?.cancel()
        val revision = durationRevision.get()
        durationJob = scope.launch {
            val values = items.toMutableList()
            var changed = false
            var lastPublish = android.os.SystemClock.elapsedRealtime()
            for (index in values.indices) {
                coroutineContext.ensureActive()
                val item = values[index]
                if (!item.mime.startsWith("video/") || item.durationMs > 0 || item.trashed) continue
                // Unknown SAF providers can be remote. Never download their media just to tag Movies.
                if (item.uri.scheme != "file" && item.uri.authority !in setOf("media", "com.android.externalstorage.documents", "com.android.providers.media.documents", "com.android.providers.downloads.documents")) continue
                if (epoch != generation.get() || access != accessStamp() || revision != durationRevision.get()) return@launch
                val duration = durationReceipts.get(item.fingerprint) ?: run {
                    val metadata = MediaFacts.read(context, item.uri, item.mime)
                    metadata.durationMs.also { durationReceipts.put(item.fingerprint, it) }
                }
                coroutineContext.ensureActive()
                if (duration > 0) {
                    values[index] = item.copy(durationMs = duration)
                    changed = true
                }
                if (changed && android.os.SystemClock.elapsedRealtime() - lastPublish >= 750) {
                    publish(values.toList(), epoch, access, revision)
                    lastPublish = android.os.SystemClock.elapsedRealtime()
                }
                kotlinx.coroutines.yield()
            }
            if (changed && epoch == generation.get() && access == accessStamp()) {
                publish(values, epoch, access, revision)
                if (!scanFailed.value) persist(values, epoch, access, revision)
            }
        }
    }
    private fun accessStamp() = cache.accessStamp(documentRoots.value)

    @Synchronized private fun persist(items: List<LocalMedia>, epoch: Long, stamp: String, revision: Long? = null) {
        if (epoch != generation.get() || stamp != accessStamp() || revision != null && revision != durationRevision.get()) return
        cache.write(stamp, items)
    }

    @Synchronized private fun publish(items: List<LocalMedia>, epoch: Long, stamp: String, revision: Long? = null) {
        if (epoch != generation.get() || stamp != accessStamp() || revision != null && revision != durationRevision.get()) return
        permissionStamp = stamp
        latestIndex.value = items
        indexReady.value = true
    }

    @Synchronized fun refresh() {
        accessRevision.value++
        if (app.televault.domain.DeviceIndexAccess.invalidate(permissionStamp, accessStamp(), cache.limitedVisualAccess())) {
            generation.incrementAndGet()
            latestIndex.value = emptyList()
            indexReady.value = false
        }
        refreshes.tryEmit(Unit)
    }
    fun close() {
        scope.cancel()
        clearCache()
    }

    fun trimThumbnails() {
        thumbnails.evictAll()
    }

    @Synchronized fun clearCache() {
        generation.incrementAndGet()
        durationJob?.cancel()
        durationReceipts.evictAll()
        thumbnails.evictAll()
        latestIndex.value = emptyList()
        indexReady.value = false
        permissionStamp = ""
        documentRoots.value = emptyList()
        observedFolders.value = emptyList()
    }
    suspend fun load(includeAllFiles: Boolean = true): List<LocalMedia> = scanMutex.withLock { scan(includeAllFiles) {} }
    private suspend fun scan(includeAllFiles: Boolean, partial: suspend (List<LocalMedia>) -> Unit): List<LocalMedia> = withContext(Dispatchers.IO) {
        val progressive = changes.value.isEmpty()
        val result = mutableListOf<LocalMedia>()
        val indexedPaths = mutableSetOf<String>()
        val indexedUris = mutableMapOf<Uri, String>()
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external")).forEach { collection ->
            try {
                val documents = collection == MediaStore.Files.getContentUri("external")
                val visual = !documents && collection != MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val columns = mutableListOf("_id", "_display_name", "mime_type", "_size", "date_added", "date_modified")
                if (collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) columns += "duration"
                if (visual) columns += listOf("datetaken", "bucket_display_name", "width", "height")
                if (Build.VERSION.SDK_INT >= 29) columns += "relative_path"
                if (Build.VERSION.SDK_INT >= 30) columns += "is_trashed"
                if (includeAllFiles && DeviceFileAccess.granted(context)) columns += "_data"
                // Files exposes only indexed files that Android permits this app to read.
                // Optional all-files access supplements this index; provider SAF trash remains separate.
                val selection = if (documents) "(mime_type IS NULL OR (mime_type NOT LIKE 'image/%' AND mime_type NOT LIKE 'video/%' AND mime_type NOT LIKE 'audio/%'))" else null
                val query = if (Build.VERSION.SDK_INT >= 30) {
                    val args = android.os.Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        if (selection != null) putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_added DESC")
                    }
                    context.contentResolver.query(collection, columns.toTypedArray(), args, null)
                } else {
                    context.contentResolver.query(collection, columns.toTypedArray(), selection, null, "date_added DESC")
                }
                query?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getColumnIndex("_data").takeIf { it >= 0 }?.let { index -> cursor.getString(index)?.let { path -> runCatching { indexedPaths.add(java.io.File(path).canonicalPath) } } }
                        val id = cursor.getLong(0)
                        val uri = ContentUris.withAppendedId(collection, id)
                        cursor.getColumnIndex("_data").takeIf { it >= 0 }?.let { index -> cursor.getString(index)?.let { path -> runCatching { indexedUris[uri] = java.io.File(path).canonicalPath } } }
                        val name = cursor.getString(1) ?: "Media"
                        val rawMime = cursor.getString(2) ?: if (collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) {
                            "video/mp4"
                        } else if (visual) {
                            "image/jpeg"
                        } else if (documents) {
                            "application/octet-stream"
                        } else {
                            "audio/mpeg"
                        }
                        val mime = app.televault.domain.MediaCategories.videoMime(name, rawMime)
                        val size = cursor.getLong(3)
                        val taken = if (visual) cursor.getLong(cursor.getColumnIndexOrThrow("datetaken")) else 0L
                        coroutineContext.ensureActive()
                        val date = if (taken > 0) {
                            taken
                        } else if (!visual && cursor.getLong(4) > 0) {
                            cursor.getLong(4) * 1000
                        } else {
                            cursor.getLong(5).coerceAtLeast(0) * 1000
                        }
                        val path = if (Build.VERSION.SDK_INT >= 29) cursor.getString(cursor.getColumnIndexOrThrow("relative_path")) else null
                        val folder = path?.trimEnd('/') ?: if (visual) {
                            cursor.getString(cursor.getColumnIndexOrThrow("bucket_display_name")) ?: "Gallery"
                        } else if (documents) {
                            "Documents"
                        } else {
                            "Audio"
                        }
                        if (app.televault.domain.DeviceFileVisibility.visible(folder) && app.televault.domain.DeviceFileVisibility.visible(indexedUris[uri])) result += LocalMedia(uri, name, mime, size, date, cursor.getLong(5), folder, Build.VERSION.SDK_INT >= 30 && cursor.getInt(cursor.getColumnIndexOrThrow("is_trashed")) != 0, indexedUris[uri], if (visual) cursor.getInt(cursor.getColumnIndexOrThrow("width")) else 0, if (visual) cursor.getInt(cursor.getColumnIndexOrThrow("height")) else 0, cursor.getLong(4).coerceAtLeast(0) * 1000, durationMs = cursor.getColumnIndex("duration").takeIf { it >= 0 }?.let { cursor.getLong(it).coerceAtLeast(0) } ?: 0)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Denied categories/optional vendor columns must not prevent the raw/SAF scan.
                if (includeAllFiles && !DeviceFileAccess.granted(context) && permissionStamp == accessStamp()) {
                    result += changes.value.filter { it.uri.toString().startsWith("$collection/") }
                    scanFailed.value = true
                }
            }
        }
        // MediaStore results become visible before a potentially large shared-storage traversal.
        if (progressive) partial(result.sortedByDescending { it.date })
        val rootsToScan = if (includeAllFiles) documentRoots.value else emptyList()
        scannedFiles.value = 0
        if (includeAllFiles && DeviceFileAccess.granted(context)) {
            result += DeviceFileAccess.scan(context, indexedPaths, { count -> scannedFiles.value = count }) { found ->
                // Initial discovery is progressive; refresh keeps the complete previous snapshot.
                if (progressive) partial((result + found).sortedByDescending { it.date })
            }
        }
        val visited = mutableSetOf<String>()
        for (rootUri in rootsToScan) {
            coroutineContext.ensureActive()
            try {
                val root = DocumentFile.fromTreeUri(context, rootUri) ?: continue
                val pending = java.util.ArrayDeque<Pair<DocumentFile, String>>()
                pending.add(root to (root.name ?: "Documents"))
                while (pending.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val (directory, path) = pending.removeFirst()
                    val identity = directory.uri.authority + ":" + DocumentsContract.getDocumentId(directory.uri)
                    if (!visited.add(identity)) continue
                    for (entry in directory.listFiles()) {
                        coroutineContext.ensureActive()
                        if (entry.isDirectory) {
                            val next = "$path/${entry.name ?: "Folder"}"
                            if (app.televault.domain.DeviceFileVisibility.visible(next)) pending.add(entry to next)
                        } else if (entry.isFile && entry.canRead()) {
                            val mime = app.televault.domain.MediaCategories.videoMime(entry.name, entry.type ?: "application/octet-stream")
                            val id = entry.uri.authority + ":" + DocumentsContract.getDocumentId(entry.uri)
                            if (!visited.add(id)) continue
                            val modified = entry.lastModified()
                            scannedFiles.value++
                            if (app.televault.domain.DeviceFileVisibility.visible(path)) result += LocalMedia(entry.uri, entry.name ?: "Document", mime, entry.length(), modified, modified / 1000, path)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                // A revoked grant exposes no files; the user can select the folder again.
            } catch (_: IllegalArgumentException) {
                // Provider removed or no longer a document tree.
            }
        }
        val knownDurations = changes.value.filter { it.durationMs > 0 }.associate { it.fingerprint to it.durationMs }
        val unique = result.map { if (it.durationMs == 0L) it.copy(durationMs = knownDurations[it.fingerprint] ?: durationReceipts.get(it.fingerprint) ?: 0L) else it }.distinctBy { indexedUris[it.uri] ?: DeviceFileAccess.identity(it.uri) }.sortedByDescending { it.date }
        if (includeAllFiles && DeviceFileAccess.granted(context)) {
            val paths = unique.mapNotNull { it.physicalPath ?: DeviceFileAccess.identity(it.uri).takeIf { path -> path.startsWith("/storage/") } }
            observedFolders.value = (listOf(android.os.Environment.getExternalStorageDirectory().absolutePath) + paths.mapNotNull { java.io.File(it).parent }).distinct().take(256).sorted()
        }
        unique
    }

    fun cachedThumbnail(item: LocalMedia, pixels: Int): Bitmap? {
        thumbnails.get("${item.fingerprint}:$pixels")?.let { return it }
        // Reuse an already sharp tile for the strip/smaller tile; never request a tiny replacement.
        for (size in intArrayOf(192, 384, 640, 768, 960)) {
            if (size > pixels) thumbnails.get("${item.fingerprint}:$size")?.let { return it }
        }
        return null
    }
    suspend fun thumbnail(item: LocalMedia, pixels: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!item.mime.startsWith("image/") && !item.mime.startsWith("video/")) return@withContext null
        val key = "${item.fingerprint}:$pixels"
        cachedThumbnail(item, pixels)?.let { return@withContext it }
        runCatching {
            val providerThumbnail = if (Build.VERSION.SDK_INT >= 29 && item.uri.scheme == "content") runCatching { context.contentResolver.loadThumbnail(item.uri, android.util.Size(pixels, pixels), null) }.getOrNull() else null
            if (providerThumbnail != null) {
                providerThumbnail
            } else if (item.mime.startsWith("video/")) {
                MediaMetadataRetriever().let { retriever ->
                    try {
                        retriever.setDataSource(context, item.uri)
                        if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, pixels, pixels) else null
                    } finally {
                        retriever.release()
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val sample = app.televault.domain.ThumbnailQuality.sample(bounds.outWidth, bounds.outHeight, pixels)
                context.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.let { MediaTools.orientThumbnail(context, item.uri, it) }
            }
        }.getOrNull()?.also { thumbnails.put(key, it) }
    }
}
