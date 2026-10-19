package app.televault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.domain.LibraryPath
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val MediaEntry.libraryTime: Long get() = local?.addedAt?.takeIf { it > 0 } ?: cloud?.sourceAddedAt?.takeIf { it > 0 } ?: date
internal data class LibraryItem(val file: MediaEntry, val parent: String)
private fun libraryParent(item: MediaEntry, cloud: Boolean, folders: Collection<String>): String {
    if (cloud && item.cloud?.album in folders) return "Folders/" + item.cloud!!.album
    if (cloud) return libraryCategory(item) + item.cloud?.album?.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
    val physical = item.local?.physicalPath ?: item.local?.uri?.takeIf { it.scheme == "file" }?.path
    if (physical?.startsWith("/storage/emulated/0/") == true) return "Internal storage/" + physical.removePrefix("/storage/emulated/0/").substringBeforeLast('/', "")
    if (physical?.startsWith("/storage/") == true) {
        val relative = physical.removePrefix("/storage/")
        return "Storage ${relative.substringBefore('/')}/" + relative.substringAfter('/', "").substringBeforeLast('/', "")
    }
    return (item.local?.uri?.authority?.takeIf { it != "media" }?.let { "Folder access ($it)/" } ?: "Internal storage/") + item.local?.folder.orEmpty()
}

internal data class LibraryProjectionScope(val epoch: Long, val cloud: Boolean, val folder: String, val category: String, val recent: Boolean, val search: String, val sort: String, val filter: MediaFilterOptions) {
    val flat: Boolean get() = recent || search.isNotBlank() || category != "All files" || filter.category != app.televault.domain.MediaCategories.Category.ALL
    fun allows(file: MediaEntry, folders: Collection<String>): Boolean = runCatching {
        val parent = LibraryPath.clean(libraryParent(file, cloud, folders))
        (file.cloud?.trashedAt == 0L || file.local?.trashed == false) &&
            LibraryPath.within(folder, parent) && filter.allows(file) &&
            (category == "All files" || libraryCategory(file) == category || category == "Movies" && file.mediaCategories and app.televault.domain.MediaCategories.Category.MOVIES.mask != 0) &&
            (search.isBlank() || file.name.contains(search, true) || parent.contains(search, true))
    }.getOrDefault(false)
}
internal data class LibraryProjectionInput(val files: List<MediaEntry>, val cloudFolders: List<String>)
internal data class LibraryProjection(val direct: List<LibraryItem> = emptyList(), val directories: List<Map.Entry<String, List<MediaEntry>>> = emptyList(), val filteredFiles: List<MediaEntry> = emptyList(), val counts: Map<String, Int> = emptyMap())
private data class LibraryDirectory(override val key: String, override val value: List<MediaEntry>, val time: Long, val bytes: Long) : Map.Entry<String, List<MediaEntry>>

internal suspend fun projectLibrary(input: LibraryProjectionInput, scope: LibraryProjectionScope): LibraryProjection {
    val cloudFolders = input.cloudFolders.toHashSet()
    val filtered = ArrayList<LibraryItem>()
    val counts = hashMapOf<String, Int>()
    input.files.forEachIndexed { index, file ->
        if (index % 128 == 0) coroutineContext.ensureActive()
        if (scope.allows(file, cloudFolders)) {
            filtered.add(LibraryItem(file, LibraryPath.clean(libraryParent(file, scope.cloud, cloudFolders))))
            val category = libraryCategory(file)
            counts[category] = (counts[category] ?: 0) + 1
        }
    }
    val values = filtered.filter { scope.flat || it.parent == scope.folder }
    val fileOrder = when (scope.sort) {
        "Oldest" -> compareBy<LibraryItem> { it.file.libraryTime }
        "Name" -> compareBy { it.file.name.lowercase(java.util.Locale.ROOT) }
        "Name descending" -> compareByDescending { it.file.name.lowercase(java.util.Locale.ROOT) }
        "Smallest" -> compareBy { it.file.size }
        "Type" -> compareBy<LibraryItem> { it.file.mime }.thenBy { it.file.name.lowercase(java.util.Locale.ROOT) }
        "Size" -> compareByDescending { it.file.size }
        else -> compareByDescending { it.file.libraryTime }
    }
    val direct = values.sortedWith(fileOrder.thenBy { it.file.id })
    coroutineContext.ensureActive()
    if (scope.flat) return LibraryProjection(direct, filteredFiles = filtered.map { it.file }, counts = counts)
    val groups = linkedMapOf<String, MutableList<MediaEntry>>()
    filtered.forEachIndexed { index, item ->
        if (index % 128 == 0) coroutineContext.ensureActive()
        LibraryPath.child(scope.folder, item.parent)?.let { groups.getOrPut(it) { arrayListOf() }.add(item.file) }
    }
    if (scope.cloud && !scope.filter.active) {
        input.cloudFolders.forEach { path ->
            runCatching { LibraryPath.child(scope.folder, "Folders/${LibraryPath.clean(path)}") }.getOrNull()?.let { groups.putIfAbsent(it, arrayListOf()) }
        }
    }
    // Aggregate each directory once. Comparisons never walk its complete file list.
    val directories = groups.map { (key, items) ->
        var time = 0L
        var bytes = 0L
        items.forEachIndexed { index, file ->
            if (index % 128 == 0) coroutineContext.ensureActive()
            val updated = file.local?.modified?.let { app.televault.domain.CaptureTime.milliseconds(it) }?.coerceAtLeast(file.libraryTime) ?: file.cloud?.sourceModifiedAt?.coerceAtLeast(file.libraryTime) ?: file.libraryTime
            time = maxOf(time, updated)
            val size = file.size.coerceAtLeast(0)
            bytes = if (size > Long.MAX_VALUE - bytes) Long.MAX_VALUE else bytes + size
        }
        LibraryDirectory(key, items, time, bytes)
    }
    val folderOrder = when (scope.sort) {
        "Oldest" -> compareBy<LibraryDirectory> { it.time }
        "Name", "Type" -> compareBy { it.key.lowercase(java.util.Locale.ROOT) }
        "Name descending" -> compareByDescending { it.key.lowercase(java.util.Locale.ROOT) }
        "Size" -> compareByDescending { it.bytes }
        "Smallest" -> compareBy { it.bytes }
        else -> compareByDescending { it.time }
    }
    coroutineContext.ensureActive()
    return LibraryProjection(direct, directories.sortedWith(folderOrder.thenBy { it.key.lowercase(java.util.Locale.ROOT) }), filtered.map { it.file }, counts)
}

/** Browsing never enables a backup subscription. Backup callbacks are explicit user actions. */
@Composable
internal fun FileLibrary(files: List<MediaEntry>, cloud: Boolean, chooseCloud: (Boolean) -> Unit, selected: Set<String>, selection: (Set<String>) -> Unit, open: (MediaEntry, List<MediaEntry>) -> Unit, backup: ((MediaEntry) -> Unit)?, search: String, changeSearch: (String) -> Unit, model: DriveViewModel? = null, backedSources: Set<String> = emptySet(), access: (() -> Unit)? = null, chooseFolder: (() -> Unit)? = null, refresh: (() -> Unit)? = null, utilities: ((String) -> Unit)? = null, fileAction: ((MediaEntry, String) -> Unit)? = null, cloudFolders: List<String> = emptyList(), cloudAvailable: Boolean = true, loading: Boolean = false, scanFailed: Boolean = false, modifier: Modifier = Modifier, shownFilesChanged: (Set<String>) -> Unit = {}, scroll: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(), leadingContent: (@Composable () -> Unit)? = null, initialFolder: String = "", initialCategory: String = "All files", initialRecent: Boolean = false, locationChanged: (String, String, Boolean) -> Unit = { _, _, _ -> }) {
    val offlineIds by (model?.offlineIds ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())).collectAsStateWithLifecycle()
    var folder by rememberSaveable(cloud) { mutableStateOf(initialFolder) }
    var category by rememberSaveable(cloud) { mutableStateOf(initialCategory) }
    var temporarySort by rememberSaveable(cloud) { mutableStateOf("Newest") }
    val sortFlow = remember(model, cloud) { model?.preferences?.fileSort(cloud) ?: kotlinx.coroutines.flow.MutableStateFlow("Newest") }
    val savedSort by sortFlow.collectAsStateWithLifecycle(initialValue = "Newest")
    val sort = if (model == null) temporarySort else savedSort
    val sortScope = rememberCoroutineScope()
    val latestSelection by rememberUpdatedState(selected)
    val mediaFilters = rememberMediaFilters(model) { selection(emptySet()) }
    val mediaFilter = mediaFilters.options
    val backupState by (model?.contentState ?: kotlinx.coroutines.flow.MutableStateFlow(app.televault.data.DriveSnapshot())).collectAsStateWithLifecycle()
    var folderBackupMenu by remember { mutableStateOf<Map.Entry<String, List<MediaEntry>>?>(null) }
    var recent by rememberSaveable(cloud) { mutableStateOf(initialRecent) }
    SideEffect { locationChanged(folder, category, recent) }
    var menu by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cloud, folder, category, recent, search, sort, mediaFilter) { scroll.scrollToItem(0) }
    val projectionScope = LibraryProjectionScope(model?.playbackEpoch ?: 0L, cloud, folder, category, recent, search, sort, mediaFilter)
    val projection = rememberBackgroundProjection(projectionScope, LibraryProjectionInput(files, cloudFolders), LibraryProjection()) { input -> projectLibrary(input, projectionScope) }
    val direct = projection.value.direct
    val directories = projection.value.directories
    val flat = projectionScope.flat
    fun authoritativeFiles(ids: Set<String>? = null): Sequence<MediaEntry> = when {
        model == null -> files.asSequence().filter { ids == null || it.id in ids }
        cloud -> model.state.value.files.asSequence().filter { ids == null || it.id in ids }.map { MediaEntry(it.id, it.name, it.mime, it.size, it.date, cloud = it) }
        else -> model.localMedia.changes.value.asSequence().filter { ids == null || it.uri.toString() in ids }.map { MediaEntry(it.uri.toString(), it.name, it.mime, it.size, it.date, local = it) }
    }
    fun allowsLeaf(item: MediaEntry): Boolean = projectionScope.allows(item, cloudFolders) && (projectionScope.flat || runCatching { LibraryPath.clean(libraryParent(item, cloud, cloudFolders)) == folder }.getOrDefault(false))
    fun currentFile(item: MediaEntry): MediaEntry? = authoritativeFiles(setOf(item.id)).firstOrNull()?.takeIf { (model?.playbackEpoch ?: 0L) == projectionScope.epoch && allowsLeaf(it) }
    fun selectCurrent(ids: Set<String>) {
        if ((model?.playbackEpoch ?: 0L) != projectionScope.epoch) return
        val eligible = authoritativeFiles(ids).filter { allowsLeaf(it) }.map { it.id }.toSet()
        selection(eligible)
    }
    fun openCurrent(item: MediaEntry) {
        val current = currentFile(item) ?: return
        val painted = direct.mapTo(hashSetOf()) { it.file.id }
        val currentEntries = authoritativeFiles(painted).filter { allowsLeaf(it) }.toList()
        open(current, currentEntries)
    }
    LaunchedEffect(direct, projectionScope, files, cloudFolders, backupState.files) {
        val eligible = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val painted = direct.mapTo(hashSetOf()) { it.file.id }
            authoritativeFiles(painted).filter { allowsLeaf(it) }.map { it.id }.toSet()
        }
        shownFilesChanged(eligible)
        if (latestSelection.isNotEmpty()) selectCurrent(latestSelection)
    }
    BackHandler(selected.isEmpty() && (folder.isNotEmpty() || flat)) {
        if (search.isNotBlank()) {
            changeSearch("")
        } else if (flat) {
            recent = false
            category = "All files"
            if (mediaFilter.category != app.televault.domain.MediaCategories.Category.ALL) mediaFilters.select(app.televault.domain.MediaCategories.Category.ALL)
        } else {
            folder = LibraryPath.parent(folder)
        }
    }
    folderBackupMenu?.let { directory ->
        val paths = directory.value.mapNotNull { it.local?.folder }.toSet()
        val subscriptions = backupState.backups.filter { backup -> backup.deviceFolder in paths && backup.deviceFolder != null }
        AlertDialog(onDismissRequest = { folderBackupMenu = null }, title = { Text(directory.key.substringAfterLast('/')) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label("Automatic backup is opt-in. Choose this exact folder in Android's folder picker to include every supported file type; browsing alone never starts backup.", "স্বয়ংক্রিয় ব্যাকআপ আপনার অনুমতিতেই হবে। সব ফাইলের জন্য Android picker-এ এই ফোল্ডারটি বাছুন; শুধু দেখলে ব্যাকআপ চালু হয় না।"))
                if (subscriptions.isNotEmpty()) {
                    TextButton({
                        subscriptions.forEach { model?.stopBackup(it.id) }
                        folderBackupMenu = null
                    }) { Text(label("Stop existing media backup", "চালু মিডিয়া ব্যাকআপ বন্ধ করুন")) }
                }
                backupState.backups.filter { backup ->
                    if (backup.uri.isBlank()) {
                        false
                    } else {
                        runCatching {
                            val tree = android.net.Uri.parse(backup.uri)
                            val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
                            val externalPath = if (root.substringBefore(':') == "primary") "Internal storage/" + root.substringAfter(':') else "Storage " + root.substringBefore(':') + "/" + root.substringAfter(':')
                            directory.key.trimEnd('/') == externalPath.trimEnd('/') || directory.key == "Folder access (${tree.authority})/${backup.name}"
                        }.getOrDefault(false)
                    }
                }.forEach { backup ->
                    TextButton({
                        model?.stopBackup(backup.id)
                        folderBackupMenu = null
                    }) { Text(label("Stop: ", "বন্ধ করুন: ") + backup.name) }
                }
            }
        }, confirmButton = {
            TextButton({
                folderBackupMenu = null
                val path = directory.key
                val initial = when {
                    path.startsWith("Internal storage/") -> android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + path.removePrefix("Internal storage/"))
                    path.startsWith("Storage ") -> path.removePrefix("Storage ").let { android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", it.substringBefore('/') + ":" + it.substringAfter('/', "")) }
                    else -> directory.value.firstOrNull()?.local?.uri?.takeIf { android.provider.DocumentsContract.isTreeUri(it) }
                }
                utilities?.invoke("Automatic backup folder:" + initial?.toString().orEmpty())
            }) { Text(label("Choose & enable backup", "বেছে ব্যাকআপ চালু করুন")) }
        }, dismissButton = { TextButton({ folderBackupMenu = null }) { Text(label("Cancel", "বাতিল")) } })
    }
    val focusPositions = remember(directories, direct) { direct.mapIndexed { index, entry -> entry.file.id to index + directories.size + 1 }.toMap() }
    LocateBrowseFocus(focusPositions, { scroll.firstVisibleItemIndex }, { scroll.scrollToItem(it) }, { scroll.animateScrollBy(it, tween(180)) })
    val managerChrome = rememberFileChrome(scroll, cloud, folder, category, search)
    Box(modifier.fillMaxSize().nestedScroll(managerChrome.connection)) {
        LazyColumn(Modifier.fillMaxSize().browseFocusViewport().clipToBounds(), state = scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = browseFocusBottom(140.dp)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "browse-introduction") {
                Column(Modifier.fillMaxWidth()) {
                    leadingContent?.invoke()
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!cloud, {
                            chooseCloud(false)
                            selection(emptySet())
                        }, label = { Text(label("On device", "ফোনে")) }, leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(16.dp)) })
                        FilterChip(cloud, {
                            chooseCloud(true)
                            selection(emptySet())
                        }, enabled = cloudAvailable, label = { Text(label("Cloud E2EE", "ক্লাউড E2EE")) }, leadingIcon = { Icon(Icons.Outlined.CloudDone, null, Modifier.size(16.dp)) })
                        if (refresh != null) IconButton(refresh) { Icon(Icons.Outlined.Refresh, label("Refresh files", "ফাইল রিফ্রেশ")) }
                    }
                    MediaFilterBar(mediaFilters)
                    if (folder.isNotBlank()) Text(label("Filters apply only inside this folder", "ফিল্টার শুধু এই ফোল্ডারের মধ্যে কাজ করে"), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        OutlinedTextField(search, changeSearch, Modifier.fillMaxWidth().padding(vertical = 4.dp), singleLine = true, shape = RoundedCornerShape(24.dp), placeholder = { Text(label("Search files and folders", "ফাইল ও ফোল্ডার খুঁজুন")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (search.isNotEmpty()) IconButton({ changeSearch("") }) { Icon(Icons.Outlined.Close, label("Clear search", "সার্চ মুছুন")) } })
                        if (folder.isBlank() && search.isBlank() && category == "All files" && !recent) {
                            FileCategoryCards(projection.value.filteredFiles, counts = projection.value.counts) { type ->
                                category = type
                                selection(emptySet())
                                recent = type == "Recent"
                                if (type == "Recent") category = "All files"
                            }
                        } else {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("All files", "Documents", "Audio", "Photos", "Videos", "Movies", "Apps", "Archives", "Other files").forEach { type ->
                                    FilterChip(category == type, {
                                        category = type
                                        selection(emptySet())
                                        recent = folder.isEmpty() && type != "All files"
                                    }, label = { Text(sectionLabel(type)) })
                                }
                            }
                        }
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (folder.isNotEmpty()) {
                                IconButton({
                                    folder = LibraryPath.parent(folder)
                                    selection(emptySet())
                                }) { Icon(Icons.Outlined.ArrowBack, label("Parent folder", "আগের ফোল্ডার")) }
                            }
                            Text(if (flat && folder.isBlank()) label("Recent files", "সম্প্রতি যোগ হওয়া ফাইল") else folder.ifBlank { if (cloud) label("Backed-up categories", "ব্যাকআপের ক্যাটাগরি") else label("Device storage", "ফোনের স্টোরেজ") }, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (category == "All files" && search.isBlank()) {
                                TextButton({
                                    recent = !recent
                                    selection(emptySet())
                                }) { Text(if (recent) label("Folders", "ফোল্ডার") else label("Recent", "সাম্প্রতিক")) }
                            }
                            if (utilities != null) {
                                var options by remember { mutableStateOf(false) }
                                Box {
                                    IconButton({ options = true }) { Icon(Icons.Outlined.MoreVert, label("Library options", "লাইব্রেরির অপশন")) }
                                    DropdownMenu(options, { options = false }) {
                                        listOf("Newest", "Oldest", "Name", "Name descending", "Size", "Smallest", "Type").forEach { key ->
                                            DropdownMenuItem(text = {
                                                Text(
                                                    (if (sort == key) "✓ " else "") + when (key) {
                                                        "Newest" -> label("Newest first", "নতুন আগে")
                                                        "Oldest" -> label("Oldest first", "পুরোনো আগে")
                                                        "Name" -> label("Name A–Z", "নাম A–Z")
                                                        "Name descending" -> label("Name Z–A", "নাম Z–A")
                                                        "Smallest" -> label("Smallest first", "ছোট ফাইল আগে")
                                                        "Type" -> label("By file type", "ফাইলের ধরন অনুযায়ী")
                                                        else -> label("Largest first", "বড় ফাইল আগে")
                                                    }
                                                )
                                            }, onClick = {
                                                temporarySort = key
                                                model?.let { source -> sortScope.launch { runCatching { source.preferences.setFileSort(cloud, key) } } }
                                                options = false
                                            })
                                        }
                                        DropdownMenuItem(text = { Text(label("Create folder", "ফোল্ডার তৈরি")) }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = {
                                            options = false
                                            utilities("Create folder")
                                        })
                                        if (!cloud && cloudAvailable) {
                                            if (folder.isNotBlank() && !flat) {
                                                DropdownMenuItem(text = { Text(label("This folder’s automatic backup", "এই ফোল্ডারের স্বয়ংক্রিয় ব্যাকআপ")) }, onClick = {
                                                    options = false
                                                    folderBackupMenu = java.util.AbstractMap.SimpleEntry(folder, projection.value.filteredFiles)
                                                })
                                            }
                                            DropdownMenuItem(text = { Text(label("Choose automatic backup folder", "স্বয়ংক্রিয় ব্যাকআপের ফোল্ডার বাছুন")) }, leadingIcon = { Icon(Icons.Outlined.CloudUpload, null) }, onClick = {
                                                options = false
                                                utilities("Automatic backup folder")
                                            })
                                        }
                                        listOf("Transfers").forEach { key ->
                                            DropdownMenuItem(text = { Text(sectionLabel(key)) }, onClick = {
                                                options = false
                                                utilities(key)
                                            })
                                        }
                                        DropdownMenuItem(text = { Text(label("Select all shown", "দেখানো সব নির্বাচন")) }, onClick = {
                                            options = false
                                            selectCurrent(direct.map { it.file.id }.toSet())
                                        })
                                        if (!cloud && chooseFolder != null) {
                                            DropdownMenuItem(text = { Text(label("Add folder access", "ফোল্ডারের অনুমতি দিন")) }, onClick = {
                                                options = false
                                                chooseFolder()
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        if (!cloud && access != null) {
                            TextButton(access, Modifier.padding(horizontal = 12.dp)) {
                                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                                Text(label("Device file access", "ফোনের ফাইলের অনুমতি"))
                            }
                        }
                    }
                }
            }
            if (directories.isEmpty() && direct.isEmpty()) {
                item {
                    Text(
                        when {
                            cloud && (loading || !projection.ready) -> label("Restoring encrypted cloud index…", "এনক্রিপ্টেড ক্লাউড তালিকা ফিরছে…")
                            cloud && scanFailed -> label("Could not restore the local index. Refresh to retry; cloud files are kept.", "লোকাল তালিকা ফেরানো যায়নি। রিফ্রেশ করুন; ক্লাউড ফাইল রয়েছে।")
                            mediaFilter.active -> label("No matches for these category filters. Reset them or turn off Clean view; backup keeps running.", "এই ক্যাটাগরি ফিল্টারে কিছু মেলেনি। রিসেট করুন বা ক্লিন ভিউ বন্ধ করুন; ব্যাকআপ চলবে।")
                            cloud -> label("Only files already backed up by this app appear here.", "শুধু এই অ্যাপ দিয়ে ব্যাকআপ করা ফাইল এখানে দেখাবে।")
                            loading || !projection.ready -> label("Reading device files…", "ফোনের ফাইল পড়ছে…")
                            scanFailed -> label("Device scan could not finish. Refresh to try again.", "ফোনের স্ক্যান শেষ হয়নি। রিফ্রেশ করে আবার চেষ্টা করুন।")
                            else -> label("No matching readable files here. Check the filter or device access.", "এখানে মিলে যাওয়া পাঠযোগ্য ফাইল নেই। ফিল্টার বা ফোনের অনুমতি দেখুন।")
                        },
                        Modifier.padding(20.dp)
                    )
                    if (mediaFilter.active) TextButton(mediaFilters.reset, Modifier.padding(horizontal = 20.dp)) { Text(label("Reset category filters", "ক্যাটাগরি ফিল্টার রিসেট")) }
                }
            }
            items(directories, key = { "folder:${it.key}" }) { directory ->
                val colors = folderColors(directory.key)
                AppSurface(
                    Modifier.clickable {
                        folder = directory.key
                        selection(emptySet())
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = colors.container
                ) {
                    ListItem(headlineContent = { Text(directory.key.substringAfterLast('/')) }, supportingContent = { Text("${directory.value.size} " + label("files", "ফাইল")) }, leadingContent = { Icon(if (cloud) Icons.Outlined.FolderCopy else Icons.Outlined.Folder, null, Modifier.size(34.dp), tint = colors.accent) }, trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!cloud && cloudAvailable && utilities != null) IconButton({ folderBackupMenu = directory }) { Icon(Icons.Outlined.CloudUpload, label("Folder backup", "ফোল্ডার ব্যাকআপ"), tint = MaterialTheme.colorScheme.primary) }
                            Icon(Icons.Outlined.ChevronRight, label("Open folder", "ফোল্ডার খুলুন"))
                        }
                    }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                }
            }
            items(direct, key = { it.file.id }) { entry ->
                val file = entry.file
                val checked = file.id in selected
                val backed = cloud || file.local?.fingerprint in backedSources
                AppSurface(Modifier.browseFocus(file.id).combinedClickable(onClick = { if (selected.isEmpty()) openCurrent(file) else selectCurrent(if (checked) selected - file.id else selected + file.id) }, onLongClick = { selectCurrent(selected + file.id) }), shape = RoundedCornerShape(20.dp), color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    ListItem(headlineContent = { Text(originalMediaName(file.name), maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(sizeLabel(file.size) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.libraryTime)) + if (!cloud && file.local?.addedAt == 0L) " · " + label("File time", "ফাইলের সময়") else "", style = MaterialTheme.typography.bodySmall) }, leadingContent = {
                        if (checked) {
                            Icon(Icons.Outlined.CheckCircle, label("Selected", "নির্বাচিত"))
                        } else if (model != null && (file.mime.startsWith("image/") || file.mime.startsWith("video/"))) {
                            MediaThumbnail(file, model, Modifier.size(48.dp), 96)
                        } else {
                            AppSurface(shape = RoundedCornerShape(14.dp), color = mediaAccentContainer(file.mime)) {
                                Icon(mediaTypeIcon(file.mime), null, Modifier.padding(12.dp), tint = mediaAccent(file.mime))
                            }
                        }
                    }, trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (file.id in offlineIds) Icon(Icons.Outlined.OfflinePin, label("Available offline", "অফলাইনে আছে"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                            if (backed) Icon(Icons.Outlined.CloudDone, label("Backed up", "ব্যাকআপ আছে"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Box {
                                IconButton({ menu = file.id }) { Icon(Icons.Outlined.MoreVert, label("File options", "ফাইলের অপশন")) }
                                DropdownMenu(menu == file.id, { menu = null }) {
                                    DropdownMenuItem(text = { Text(label("Open", "খুলুন")) }, onClick = {
                                        menu = null
                                        openCurrent(file)
                                    })
                                    DropdownMenuItem(text = { Text(label("Select", "নির্বাচন")) }, onClick = {
                                        menu = null
                                        selectCurrent(selected + file.id)
                                    })
                                    if (fileAction != null) {
                                        (listOf("Share", "Open with") + (if (file.cloud != null) listOf("Save copy", if (file.id in offlineIds) "Remove offline" else "Keep offline") else emptyList()) + listOf("Private Vault", "Move", "Copy", "Rename", "Compress ZIP", "Trash")).forEach { action ->
                                            DropdownMenuItem(text = {
                                                Text(
                                                    when (action) {
                                                        "Share" -> label("Share", "শেয়ার")
                                                        "Open with" -> label("Open with another app", "অন্য অ্যাপে খুলুন")
                                                        "Keep offline" -> label("Keep encrypted offline", "এনক্রিপ্টেড অফলাইন রাখুন")
                                                        "Remove offline" -> label("Remove offline copy", "অফলাইন কপি সরান")
                                                        "Save copy" -> label("Save copy", "কপি সেভ")
                                                        "Move" -> label("Move", "সরান")
                                                        "Copy" -> label("Copy", "কপি")
                                                        "Compress ZIP" -> label("Export ZIP copy", "ZIP কপি এক্সপোর্ট")
                                                        "Rename" -> label("Rename", "নাম বদলান")
                                                        "Private Vault" -> label("Move to Private Vault", "প্রাইভেট ভল্টে সরান")
                                                        else -> label("Move to Trash", "ট্র্যাশে সরান")
                                                    }
                                                )
                                            }, onClick = {
                                                menu = null
                                                currentFile(file)?.let { fileAction(it, action) }
                                            })
                                        }
                                    }
                                    if (!cloud && backup != null) {
                                        DropdownMenuItem(text = { Text(label("Backup", "ব্যাকআপ")) }, leadingIcon = { Icon(Icons.Outlined.CloudUpload, null) }, onClick = {
                                            menu = null
                                            currentFile(file)?.let(backup)
                                        })
                                        if (fileAction != null) {
                                            DropdownMenuItem(text = { Text(label("Backup and delete from device", "ব্যাকআপ করে ডিভাইস থেকে সরান")) }, onClick = {
                                                menu = null
                                                currentFile(file)?.let { fileAction(it, "Backup and trash") }
                                            })
                                        }
                                    }
                                    if (cloud && fileAction != null) {
                                        DropdownMenuItem(text = { Text(label("Remove backed-up device copy", "ব্যাকআপ হওয়া ডিভাইস কপি সরান")) }, onClick = {
                                            menu = null
                                            currentFile(file)?.let { fileAction(it, "Remove device copy") }
                                        })
                                    }
                                }
                            }
                        }
                    }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        BrowseCompactBar(managerChrome.mode == app.televault.domain.PhotoChromePolicy.Mode.COMPACT && selected.isEmpty(), Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!cloud, {
                    chooseCloud(false)
                    selection(emptySet())
                }, label = { Text(label("On device", "ফোনে")) }, leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(16.dp)) })
                FilterChip(cloud, {
                    chooseCloud(true)
                    selection(emptySet())
                }, enabled = cloudAvailable, label = { Text(label("Cloud E2EE", "ক্লাউড E2EE")) }, leadingIcon = { Icon(Icons.Outlined.CloudDone, null, Modifier.size(16.dp)) })
                if (refresh != null) IconButton(refresh) { Icon(Icons.Outlined.Refresh, label("Refresh files", "ফাইল রিফ্রেশ")) }
            }
            MediaFilterBar(mediaFilters)
        }
    }
}
