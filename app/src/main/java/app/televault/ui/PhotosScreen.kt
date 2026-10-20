package app.televault.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.AppearanceMode
import app.televault.DriveViewModel
import app.televault.InterfaceStyle
import app.televault.R
import app.televault.data.*
import app.televault.domain.TimelineZoom
import app.televault.domain.TopEdgeReveal
import app.televault.domain.TrashCooldown
import app.televault.security.LocalDeviceAuthentication
import app.televault.security.LocalDeviceLock
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

@Composable internal fun label(en: String, bn: String): String = if (LocalConfiguration.current.locales[0].language == "bn") bn else en
internal data class MediaEntry(val id: String, val name: String, val mime: String, val size: Long, val date: Long, val cloud: DriveFile? = null, val local: LocalMedia? = null) {
    val mediaCategories: Int get() = local?.mediaCategories ?: cloud?.mediaCategories ?: app.televault.domain.MediaCategories.Category.OTHER.mask
}
private enum class TrashScope { DEVICE, CLOUD }
private enum class PhotoTab { TIMELINE, GALLERY, LIBRARY, VAULT, SETTINGS }
private data class PhotoProjectionScope(val epoch: Long, val tab: PhotoTab, val section: String, val device: Boolean, val showHidden: Boolean, val filter: MediaFilterOptions, val trashFiles: Boolean, val search: String, val folder: String?, val album: String?, val allLabel: String, val favoritesLabel: String, val videosLabel: String) {
    val albumsVisible: Boolean get() = section in setOf("Albums", "Folders") && tab !in setOf(PhotoTab.VAULT, PhotoTab.SETTINGS)
    fun allows(item: MediaEntry, trashOrigins: Map<String, String>, duplicates: Set<String>, favorites: Set<String>): Boolean {
        if ((item.local != null) != device) return false
        val media = item.mime.startsWith("image/") || item.mime.startsWith("video/")
        val trashed = item.cloud?.trashedAt?.let { it > 0 } ?: (item.local?.trashed ?: false)
        val type = when (section) {
            "Trash" -> trashed && if (trashFiles) item.cloud?.trashOrigin == "library" || trashOrigins[item.id] == "library" || !media else item.cloud?.trashOrigin != "library" && trashOrigins[item.id] != "library" && media
            "Audio" -> !trashed && item.mime.startsWith("audio/")
            "All files" -> !trashed
            "Documents" -> !trashed && !media && !item.mime.startsWith("audio/")
            "Favorites" -> !trashed && (item.cloud?.favorite == true || item.id in favorites)
            "Duplicates" -> !trashed && item.id in duplicates
            "Photos" -> !trashed && item.mime.startsWith("image/")
            "Videos" -> !trashed && item.mime.startsWith("video/")
            else -> !trashed && media
        }
        val clean = tab != PhotoTab.GALLERY || section == "Trash" || showHidden || item.local?.let { !it.galleryHidden && app.televault.domain.GalleryVisibility.clean(it.mime, it.name, it.folder, it.width, it.height, it.size) } != false
        return clean && type && (section == "Trash" || filter.allows(item)) && item.name.contains(search, true) && (folder == null || item.cloud?.album == folder || item.local?.folder == folder) && (album == null || item.cloud?.albumIds?.contains(album) == true)
    }
}
private data class PhotoProjectionInput(val cloud: List<DriveFile>, val local: List<LocalMedia>, val trash: List<DeviceFileTrashItem>, val albums: List<CloudAlbum>, val favorites: Set<String>, val trashOrigins: Map<String, String>)
private data class PhotoProjection(val cloud: List<MediaEntry> = emptyList(), val device: List<MediaEntry> = emptyList(), val base: List<MediaEntry> = emptyList(), val visible: List<MediaEntry> = emptyList(), val duplicates: Set<String> = emptySet(), val deviceAlbums: List<AlbumTile> = emptyList(), val cloudAlbums: List<AlbumTile> = emptyList())

private suspend fun projectPhotos(input: PhotoProjectionInput, scope: PhotoProjectionScope): PhotoProjection {
    val job = currentCoroutineContext()
    val cloud = input.cloud.mapIndexed { index, file ->
        if (index % 128 == 0) job.ensureActive()
        MediaEntry(file.id, file.name, file.mime, file.size, file.date, cloud = file)
    }
    val device = (input.local.asSequence() + input.trash.asSequence().map { it.local }).distinctBy { it.uri }.mapIndexed { index, file ->
        if (index % 128 == 0) job.ensureActive()
        MediaEntry(file.uri.toString(), file.name, file.mime, file.size, file.date, local = file)
    }.toList()
    val base = if (scope.device) device else cloud
    val duplicates = if (scope.section == "Duplicates") cloud.groupBy { it.cloud!!.hash }.filterValues { it.size > 1 }.values.flatten().mapTo(hashSetOf()) { it.id } else emptySet()
    val visible = if (scope.tab in setOf(PhotoTab.SETTINGS, PhotoTab.VAULT) || scope.tab == PhotoTab.LIBRARY && scope.section !in setOf("Trash", "Transfers")) {
        emptyList()
    } else {
        base.filterIndexed { index, item ->
            if (index % 128 == 0) job.ensureActive()
            scope.allows(item, input.trashOrigins, duplicates, input.favorites)
        }
    }
    if (!scope.albumsVisible) return PhotoProjection(cloud, device, base, visible, duplicates)
    val deviceVisual = if (scope.tab != PhotoTab.GALLERY) emptyList() else device.filter { item -> scope.filter.allows(item) && item.local?.trashed == false && (item.mime.startsWith("image/") || item.mime.startsWith("video/")) && (scope.showHidden || item.local.let { file -> !file.galleryHidden && app.televault.domain.GalleryVisibility.clean(file.mime, file.name, file.folder, file.width, file.height, file.size) }) }
    val localFolders = deviceVisual.groupBy { it.local!!.folder }
    val cameraFolder = localFolders.keys.firstOrNull { it.substringAfterLast('/').equals("Camera", true) }
    val deviceAlbums = buildList {
        if (scope.tab == PhotoTab.GALLERY) {
            add(AlbumTile("device:all", scope.allLabel, deviceVisual, collage = true))
            cameraFolder?.let { path -> add(AlbumTile("device:$path", path.substringAfterLast('/'), localFolders.getValue(path), folder = path)) }
            add(AlbumTile("device:favorites", scope.favoritesLabel, deviceVisual.filter { it.id in input.favorites }, section = "Favorites"))
            add(AlbumTile("device:videos", scope.videosLabel, deviceVisual.filter { it.mime.startsWith("video/") }, section = "Videos"))
            localFolders.entries.filter { it.key != cameraFolder }.map { entry -> entry to (entry.value.maxOfOrNull { app.televault.domain.LocalFolderOrder.updatedAt(it.date, it.local!!.modified) } ?: 0L) }.sortedByDescending { it.second }.forEach { (entry, _) -> add(AlbumTile("device:${entry.key}", entry.key.substringAfterLast('/'), entry.value, folder = entry.key)) }
        }
    }.filter { scope.search.isBlank() || it.name.contains(scope.search, true) }
    // One pass over media memberships replaces a complete library scan for every album.
    val folders = linkedMapOf<String, MutableList<MediaEntry>>()
    val albums = hashMapOf<String, MutableList<MediaEntry>>()
    cloud.forEachIndexed { index, item ->
        if (index % 128 == 0) job.ensureActive()
        if (scope.filter.allows(item) && item.cloud?.trashedAt == 0L && (item.mime.startsWith("image/") || item.mime.startsWith("video/"))) {
            folders.getOrPut(item.cloud.album) { arrayListOf() }.add(item)
            item.cloud.albumIds.forEach { albums.getOrPut(it) { arrayListOf() }.add(item) }
        }
    }
    val namedFolders = input.albums.asSequence().filter { it.folder }.map { it.name }.toSet()
    val cloudAlbums = buildList {
        input.albums.forEach { entry ->
            job.ensureActive()
            val items = (if (entry.folder) folders[entry.name] else albums[entry.id]).orEmpty()
            add(AlbumTile(entry.id, entry.name, items, true, if (entry.folder) entry.name else null, if (!entry.folder) entry.id else null))
        }
        folders.forEach { (path, items) -> if (path !in namedFolders) add(AlbumTile("folder:$path", path, items, true, folder = path)) }
    }.filter { scope.search.isBlank() || it.name.contains(scope.search, true) }
    return PhotoProjection(cloud, device, base, visible, duplicates, deviceAlbums, cloudAlbums)
}
internal fun sizeLabel(bytes: Long) = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PhotosScreen(model: DriveViewModel, dynamicColor: Boolean, changeDynamicColor: suspend (Boolean) -> Unit, appearanceMode: AppearanceMode, changeAppearanceMode: suspend (AppearanceMode) -> Unit, interfaceStyle: InterfaceStyle, changeInterfaceStyle: suspend (InterfaceStyle) -> Unit, lockGraceMinutes: Int, changeLockGraceMinutes: suspend (Int) -> Unit, logout: () -> Unit, exportRequestToken: Int = 0, onExportRequestHandled: (Int) -> Unit = {}, incomingMedia: app.televault.IncomingMedia? = null, onIncomingHandled: () -> Unit = {}) {
    val state by model.contentState.collectAsStateWithLifecycle()
    val people by model.people.state.collectAsStateWithLifecycle()
    val textIndex by model.ocr.state.collectAsStateWithLifecycle()
    var universalSearch by remember { mutableStateOf(false) }
    var searchPerson by remember { mutableStateOf<String?>(null) }
    val scanningFiles by model.localMedia.scanningDocuments.collectAsStateWithLifecycle()
    val scannedFiles by model.localMedia.scannedFiles.collectAsStateWithLifecycle()
    val deviceIndexReady by model.localMedia.indexReady.collectAsStateWithLifecycle()
    val deviceScanFailed by model.localMedia.scanFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceCopyActions = rememberDeviceCopyActions(model)
    val trashActions = rememberTrashActions(model)
    var backupSelectionMenu by remember { mutableStateOf(false) }
    val crashSummary by produceState("") {
        value = withContext(Dispatchers.IO) { app.televault.RuntimeDiagnostics.summary(context.applicationContext) + "\n" + (context.applicationContext as app.televault.TeleVaultApplication).telegram.sessionDiagnostics() }
    }
    val showHiddenGallery by model.preferences.showHiddenGallery.collectAsStateWithLifecycle(initialValue = false)
    val guidanceEnabled by model.preferences.guidanceEnabled.collectAsStateWithLifecycle(initialValue = true)
    val mosaicLayout by model.preferences.mosaicLayout.collectAsStateWithLifecycle(initialValue = true)
    val returnToViewedItem by model.preferences.returnToViewedItem.collectAsStateWithLifecycle(initialValue = true)
    val resumeLastLocation by model.preferences.resumeLastLocation.collectAsStateWithLifecycle(initialValue = true)
    val tapToRevealControls by model.preferences.tapToRevealControls.collectAsStateWithLifecycle(initialValue = false)
    val animationMode by model.preferences.animationMode.collectAsStateWithLifecycle(initialValue = 0)
    val launcherName by model.preferences.launcherName.collectAsStateWithLifecycle(initialValue = app.televault.LauncherName.DEFAULT)
    val authenticate = LocalDeviceAuthentication.current
    val lockDevice = LocalDeviceLock.current
    var recoveryForm by rememberSaveable { mutableStateOf(false) }
    if (state.stage != VaultStage.READY) {
        if (state.stage == VaultStage.LOCKED && !recoveryForm) {
            UnlockScreen(unlock = { authenticate { model.quickUnlock() } }, busy = state.busy, notice = state.notice, recovery = { recoveryForm = true }, back = { authenticate { model.quickUnlock() } })
        } else {
            Column(Modifier.fillMaxSize()) {
                if (state.stage == VaultStage.LOCKED) {
                    BackHandler { recoveryForm = false }
                    TextButton({ recoveryForm = false }, Modifier.safeDrawingPadding()) {
                        Icon(Icons.Outlined.ArrowBack, null)
                        Text(label("Back to phone unlock", "ফোন আনলকে ফিরুন"))
                    }
                }
                Box(Modifier.weight(1f)) { VaultForm(state, model) }
            }
        }
        return
    }
    val restoredPosition by produceState<app.televault.data.BrowsePosition?>(null, model.playbackEpoch) {
        // Read both switches before restoring anything: Off cannot briefly redirect first.
        val options = model.preferences.browseResumeOptions.first()
        value = options.restore(if (options.location) model.browsePositions.load(model.playbackEpoch) else app.televault.data.BrowsePosition())
    }
    val initialPosition = restoredPosition
    if (initialPosition == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val telegramWarningSeen by model.preferences.telegramStorageWarningSeen.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(model) { model.refreshCloudExports() }
    val cooldown by model.preferences.trashCooldownEnabled.collectAsStateWithLifecycle(initialValue = true)
    var settingsCategory by rememberSaveable { mutableStateOf("") }
    var tab by remember(model.playbackEpoch) { mutableStateOf(PhotoTab.valueOf(initialPosition.tab)) }
    var galleryCloud by remember(model.playbackEpoch) { mutableStateOf(initialPosition.galleryCloud) }
    var previousTab by rememberSaveable { mutableStateOf(PhotoTab.TIMELINE) }
    var rootBackPresses by remember { mutableIntStateOf(0) }
    val libraryStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val tabStates = remember { mutableStateMapOf<PhotoTab, SavedTab>() }
    var section by remember(model.playbackEpoch) { mutableStateOf(initialPosition.section) }
    var search by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(initialPosition.folder) }
    var album by remember { mutableStateOf(initialPosition.album) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var libraryShownIds by remember { mutableStateOf(emptySet<String>()) }
    val mediaFilters = rememberMediaFilters(model) { selected = emptySet() }
    val mediaFilter = mediaFilters.options
    val local by model.localContent.collectAsStateWithLifecycle()
    val storedTrash by model.deviceFileTrash.items.collectAsStateWithLifecycle()
    val allTrashDates = remember(state.deviceTrashDates, storedTrash) { state.deviceTrashDates + storedTrash.associate { it.local.uri.toString() to it.trashedAt } }
    val allTrashOrigins = remember(state.deviceTrashOrigins, storedTrash) { state.deviceTrashOrigins + storedTrash.associate { it.local.uri.toString() to it.origin } }
    var showProfile by remember { mutableStateOf(false) }
    var exploreMode by remember { mutableStateOf<String?>(initialPosition.explore.takeIf { it.isNotBlank() }) }
    var allPeopleVisible by remember { mutableStateOf(false) }
    var exploreShorts by remember { mutableStateOf(false) }
    var pendingShorts by remember { mutableStateOf(false) }
    var archiveEntries by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var archiveConsent by remember { mutableStateOf(false) }
    var renameEntry by remember { mutableStateOf<MediaEntry?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var pendingVaultCopies by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    val sceneIndex by model.scenes.state.collectAsStateWithLifecycle()
    var memoryStory by remember { mutableStateOf<PhotoMemory?>(null) }
    var opening by remember { mutableStateOf<MediaEntry?>(null) }
    var viewerEntries by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var incomingBackup by remember { mutableStateOf<List<LocalMedia>?>(null) }
    var incomingBusy by remember { mutableStateOf(false) }
    LaunchedEffect(incomingMedia?.id, model.playbackEpoch) {
        val request = incomingMedia ?: return@LaunchedEffect
        incomingBusy = true
        try {
            val items = request.resolve(context)
            if (items.isEmpty()) {
                model.report("Could not read these files. Share them again from the source app.")
            } else if (request.backup) {
                incomingBackup = items
            } else {
                val entries = items.map { item -> MediaEntry(item.uri.toString(), item.name, item.mime, item.size, item.date, local = item) }
                viewerEntries = entries
                opening = entries.first()
            }
            onIncomingHandled()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            model.report("Could not open the shared files. Try again from the source app.")
            onIncomingHandled()
        } finally {
            incomingBusy = false
        }
    }
    if (incomingBusy) {
        AlertDialog(onDismissRequest = {
            incomingBusy = false
            onIncomingHandled()
        }, title = { Text("Opening shared files") }, text = { Text("Reading the selected file details…") }, confirmButton = {
            TextButton({
                incomingBusy = false
                onIncomingHandled()
            }) { Text("Cancel") }
        })
    }
    incomingBackup?.let { items ->
        AlertDialog(onDismissRequest = { incomingBackup = null }, title = { Text("Back up with TG Photos") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${items.size} selected file${if (items.size == 1) "" else "s"} will use your encrypted Photos backup. Existing verified backups are reused.")
                items.take(5).forEach { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = {
            TextButton({
                model.upload(items.map { it.uri })
                incomingBackup = null
            }) { Text("Back up") }
        }, dismissButton = { TextButton({ incomingBackup = null }) { Text("Cancel") } })
    }
    var prompt by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var exportIds by remember { mutableStateOf(setOf<String>()) }
    var budgetDismissed by remember { mutableStateOf(false) }
    var permissionFailures by remember { mutableIntStateOf(0) }
    var permissionsChanged by remember { mutableIntStateOf(0) }
    var mobileLimit by remember(state.uploadSettings.photoLimitMb) { mutableStateOf(state.uploadSettings.photoLimitMb.toString()) }
    var backupVideoMinutes by remember(state.uploadSettings.cameraVideoMinutes) { mutableIntStateOf(state.uploadSettings.cameraVideoMinutes) }
    var backupPhotosFirst by remember(state.uploadSettings.photosFirst) { mutableStateOf(state.uploadSettings.photosFirst) }
    var backupSaving by remember { mutableStateOf(false) }
    var backupSaved by remember { mutableStateOf(false) }
    var unlimitedConfirmation by remember { mutableStateOf(false) }
    var videoDataConfirmation by remember { mutableStateOf(false) }
    var setupUnlimited by rememberSaveable { mutableStateOf(false) }
    var setupCamera by rememberSaveable { mutableStateOf(true) }
    var pendingNewFolder by remember { mutableStateOf("") }
    var pendingLocalMove by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var localCopy by remember { mutableStateOf(false) }
    var density by rememberSaveable { mutableFloatStateOf(initialPosition.columns) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var galleryIntroduction by rememberSaveable { mutableStateOf(false) }
    var firstLoginPermissions by rememberSaveable { mutableStateOf(false) }
    var allFilesIntroduction by rememberSaveable { mutableStateOf(false) }
    var libraryFolders by rememberSaveable { mutableStateOf(true) }
    var libraryDevice by remember(model.playbackEpoch) { mutableStateOf(initialPosition.device) }
    var libraryPath by remember(model.playbackEpoch) { mutableStateOf(initialPosition.libraryPath) }
    var libraryCategory by remember(model.playbackEpoch) { mutableStateOf(initialPosition.libraryCategory) }
    var libraryRecent by remember(model.playbackEpoch) { mutableStateOf(initialPosition.libraryRecent) }
    var trashScope by remember { mutableStateOf(TrashScope.DEVICE) }
    var trashFiles by remember { mutableStateOf(false) }
    var permanentDelete by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var showOfflineDownloads by remember { mutableStateOf(false) }
    var telegramSessions by remember { mutableStateOf(false) }
    var changePassword by remember { mutableStateOf(false) }
    var exportLocal by remember { mutableStateOf(emptyList<LocalMedia>()) }
    var backupStatusVisible by remember { mutableStateOf(false) }
    if (telegramWarningSeen && opening == null && tab != PhotoTab.VAULT && prompt.isBlank()) CloudExportCompletion(model)
    val headerScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val revealThreshold = with(LocalDensity.current) { 88.dp.toPx() }
    // New tab/folder/filter entries start at their own top, never another grid's offset.
    val timelineGrid = remember(model.playbackEpoch, tab, section, folder, album, galleryCloud) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    LaunchedEffect(tab, section, folder, album) {
        headerScroll.state.heightOffset = 0f
        headerScroll.state.contentOffset = 0f
    }
    val edgeReveal = remember(tab, section, selected.isEmpty(), revealThreshold) { TopEdgeReveal(revealThreshold) }
    val isPhotoTimeline = tab == PhotoTab.TIMELINE && folder == null && album == null && section in setOf("All", "Photos", "Videos", "Favorites")
    val isBrowse = tab in setOf(PhotoTab.TIMELINE, PhotoTab.GALLERY, PhotoTab.LIBRARY, PhotoTab.VAULT) && section != "Transfers"
    val fileLibrary = tab == PhotoTab.LIBRARY && section !in setOf("Transfers", "Trash")
    val browseList = remember(model.playbackEpoch, tab, section, folder, album, libraryDevice) { androidx.compose.foundation.lazy.LazyListState() }
    val usesBrowseList = fileLibrary || section in setOf("Documents", "Audio", "All files") || section == "Trash" && trashFiles
    val resumeEpoch = model.playbackEpoch
    val startHost = remember(resumeEpoch) { BrowseStartHost() }
    var initialScrollApplied by remember(resumeEpoch) { mutableStateOf(false) }
    var initialScrollInterrupted by remember(resumeEpoch) { mutableStateOf(false) }
    var lastViewedId by remember(model.playbackEpoch, tab, section, folder, album, galleryCloud, libraryDevice, libraryPath, libraryCategory, exploreMode) {
        mutableStateOf(if (tab.name == initialPosition.tab && section == initialPosition.section && folder == initialPosition.folder && album == initialPosition.album && libraryPath == initialPosition.libraryPath && exploreMode.orEmpty() == initialPosition.explore) initialPosition.focusId else "")
    }
    val browseFocus = remember(model.playbackEpoch, tab, section, folder, album, galleryCloud, libraryDevice, libraryPath, libraryCategory, exploreMode) { BrowseFocusState() }
    LaunchedEffect(returnToViewedItem, opening == null, browseFocus, lastViewedId) {
        browseFocus.request(if (returnToViewedItem && opening == null && tab != PhotoTab.VAULT) lastViewedId else null)
    }
    CompositionLocalProvider(LocalBrowseFocus provides (browseFocus.takeIf { exploreMode == null && !universalSearch && !showProfile && memoryStory == null }), LocalBrowseStartHost provides startHost) {
        val positionReader by rememberUpdatedState({
            app.televault.data.BrowsePosition(
                tab.name, section, folder, album, libraryDevice, galleryCloud, libraryPath, libraryCategory, libraryRecent,
                if (usesBrowseList) browseList.firstVisibleItemIndex else timelineGrid.firstVisibleItemIndex,
                if (usesBrowseList) browseList.firstVisibleItemScrollOffset else timelineGrid.firstVisibleItemScrollOffset, density, exploreMode.orEmpty(), if (returnToViewedItem) lastViewedId else ""
            ).safe()
        })
        LaunchedEffect(resumeEpoch) {
            var previous: app.televault.data.BrowsePosition? = null
            snapshotFlow { positionReader() }.collectLatest { position ->
                val onlyScroll = previous?.copy(index = position.index, offset = position.offset) == position
                previous = position
                if (onlyScroll) delay(350)
                model.saveBrowsePosition(position, resumeEpoch)
            }
        }
        val resumeLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(resumeLifecycle, resumeEpoch) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.saveBrowsePosition(positionReader(), resumeEpoch)
            }
            resumeLifecycle.addObserver(observer)
            onDispose { resumeLifecycle.removeObserver(observer) }
        }
        val chromeDensity = LocalDensity.current.density
        val photoChrome = remember(tab, section, folder, album, libraryDevice, galleryCloud, timelineGrid, browseList, usesBrowseList, chromeDensity) {
            BrowseChromeState(
                { if (usesBrowseList) !browseList.canScrollBackward else !timelineGrid.canScrollBackward },
                { if (usesBrowseList) browseList.firstVisibleItemIndex > 0 else timelineGrid.firstVisibleItemIndex > 0 },
                chromeDensity
            )
        }
        LaunchedEffect(timelineGrid, browseList, usesBrowseList, isBrowse) {
            snapshotFlow { if (usesBrowseList) !browseList.canScrollBackward else !timelineGrid.canScrollBackward }.collect { atTop ->
                if (atTop) {
                    photoChrome.reset()
                }
            }
        }
        val introductionCleared by remember(timelineGrid, browseList, usesBrowseList) {
            derivedStateOf {
                if (usesBrowseList) browseList.firstVisibleItemIndex > 0 else timelineGrid.firstVisibleItemIndex > 0
            }
        }
        var compactChromeHeight by remember(tab, section, folder, album) { mutableStateOf(0.dp) }
        val compactChromeTransition = remember(tab, section, folder, album) { androidx.compose.animation.core.MutableTransitionState(false) }
        // Reverse scrolling must not put a second filter bar over the returning title.
        compactChromeTransition.targetState = isBrowse && tab != PhotoTab.VAULT && !fileLibrary && selected.isEmpty() && photoChrome.compactVisible
        val showTimelineDates = !compactChromeTransition.targetState
        val statusPull = remember(tab, section, selected.isEmpty(), headerScroll, edgeReveal, photoChrome, timelineGrid, browseList, usesBrowseList, isBrowse, isPhotoTimeline) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!isBrowse) return headerScroll.nestedScrollConnection.onPreScroll(available, source)
                    // Header is a real lazy-grid item. It scrolls away without resizing the viewport.
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (!isBrowse) return headerScroll.nestedScrollConnection.onPostScroll(consumed, available, source)
                    if (source == NestedScrollSource.UserInput) {
                        if (consumed.y + available.y != 0f) initialScrollInterrupted = true
                        photoChrome.connection.onPostScroll(consumed, available, source)
                        if (consumed.y + available.y < 0) backupStatusVisible = false
                        if (isPhotoTimeline && edgeReveal.pull(available.y, !timelineGrid.canScrollBackward)) backupStatusVisible = true
                    }
                    return Offset.Zero
                }
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    edgeReveal.endGesture()
                    // A reverse fling must not snap the collapsed header open mid-timeline.
                    return if (!isBrowse) headerScroll.nestedScrollConnection.onPostFling(consumed, available) else Velocity.Zero
                }
            }
        }
        fun switchTab(next: PhotoTab, rememberOrigin: Boolean = true) {
            if (next == tab) return
            tabStates[tab] = SavedTab(section, search, folder, album, libraryDevice, showSearch, galleryCloud)
            if (next == PhotoTab.SETTINGS && rememberOrigin) previousTab = tab
            val saved = tabStates[next]
            tab = next
            selected = emptySet()
            rootBackPresses = 0
            backupStatusVisible = false
            section = saved?.section ?: when (next) {
                PhotoTab.GALLERY -> "Albums"
                PhotoTab.LIBRARY -> "All files"
                else -> "All"
            }
            search = saved?.search.orEmpty()
            folder = saved?.folder
            album = saved?.album
            libraryDevice = saved?.device ?: false
            showSearch = saved?.searchOpen ?: false
            galleryCloud = saved?.galleryCloud ?: false
        }
        LaunchedEffect(exportRequestToken) {
            if (exportRequestToken > 0) {
                opening = null
                viewerEntries = null
                showProfile = false
                prompt = ""
                switchTab(PhotoTab.SETTINGS)
                settingsCategory = "Export Cloud library"
                model.refreshCloudExports()
                onExportRequestHandled(exportRequestToken)
            }
        }
        val deviceContext = (tab == PhotoTab.GALLERY && !galleryCloud) || (tab == PhotoTab.LIBRARY && libraryDevice)
        BackHandler(enabled = opening == null && !showProfile) {
            when {
                tab == PhotoTab.SETTINGS && settingsCategory.isNotBlank() -> settingsCategory = ""
                tab == PhotoTab.SETTINGS -> switchTab(previousTab, false)
                selected.isNotEmpty() -> selected = emptySet()
                showSearch -> {
                    showSearch = false
                    search = ""
                }
                backupStatusVisible -> backupStatusVisible = false
                folder != null || album != null -> {
                    folder = null
                    album = null
                    galleryCloud = false
                    section = if (folder == null && tab == PhotoTab.GALLERY) {
                        "Albums"
                    } else if (tab == PhotoTab.LIBRARY) {
                        section
                    } else {
                        "All"
                    }
                }
                tab == PhotoTab.GALLERY && (section != "Albums" || galleryCloud) -> {
                    galleryCloud = false
                    section = "Albums"
                }
                section !in setOf("All", "Albums", "Documents", "All files") -> section = when (tab) {
                    PhotoTab.GALLERY -> "Albums"
                    PhotoTab.LIBRARY -> "All files"
                    else -> "All"
                }
                else -> rootBackPresses++
            }
        }
        LaunchedEffect(permissionsChanged) {
            model.localMedia.refresh()
            model.deviceFileTrash.refresh()
        }
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) + if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        LaunchedEffect(telegramWarningSeen) {
            if (telegramWarningSeen && !model.preferences.mediaSetupSeen.first()) {
                firstLoginPermissions = true
                galleryIntroduction = true
            }
        }
        LaunchedEffect(tab, telegramWarningSeen) {
            if (telegramWarningSeen && tab == PhotoTab.GALLERY && !model.preferences.galleryIntroductionSeen.first()) galleryIntroduction = true
        }
        var enableSaverAfterPermission by remember { mutableStateOf(false) }
        var enableCameraAfterPermission by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            permissionsChanged++
            if (grants.isNotEmpty() && grants.values.none { it }) {
                permissionFailures++
                model.report("Permission was declined. You can still browse the cloud. Use Android app permission settings to grant access later.")
            }
            if (enableCameraAfterPermission) {
                enableCameraAfterPermission = false
                val visualGranted = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, if (Build.VERSION.SDK_INT >= 34) Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED else Manifest.permission.READ_MEDIA_IMAGES).any { grants[it] == true || androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED } else grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
                if (visualGranted) {
                    if (enableSaverAfterPermission) model.dataSaver(true) else model.settings(state.uploadSettings.photoLimitMb, state.uploadSettings.mobileVideos, true)
                    enableSaverAfterPermission = false
                } else {
                    enableSaverAfterPermission = false
                    model.report("Camera backup needs photo/video access. Enable it again after granting permission.")
                }
            }
        }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) model.resumeAutomaticBackups() }
        fun notifyAccess() {
            if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            if (uris.isNotEmpty()) {
                model.upload(uris)
                notifyAccess()
                section = "Transfers"
                tab = PhotoTab.LIBRARY
            }
        }
        fun persistAccess(uri: android.net.Uri, write: Boolean): Boolean = try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val directory = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            check(directory?.canRead() == true && (!write || directory.canWrite()))
            true
        } catch (_: Exception) {
            model.report("Folder access was not granted. Choose a readable/writable folder and try again.")
            false
        }
        fun savePreference(block: suspend () -> Unit) {
            scope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    model.report("Settings could not be saved. Check available storage and try again.")
                }
            }
        }
        val destination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                if (!persistAccess(uri, true)) return@rememberLauncherForActivityResult
                if (exportIds.isNotEmpty()) model.download(exportIds, uri)
                val originals = exportLocal
                exportLocal = emptyList()
                if (originals.isNotEmpty()) {
                    scope.launch {
                        try {
                            LocalFileActions.copyOrMove(context, originals, uri, "", true)
                            model.report("Device originals saved as copies.")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            model.report("Could not save these copies. Originals were kept.")
                        }
                    }
                }
                exportIds = emptySet()
                selected = emptySet()
            }
        }
        val documentFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    model.addDeviceDocumentFolder(uri)
                    permissionsChanged++
                } catch (_: Exception) {
                    model.report("Folder access was not granted. Select the folder again; no backup was enabled.")
                }
            }
        }
        val activityOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(activityOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    permissionsChanged++
                    model.resumeAutomaticBackups()
                    model.restoreVisibleIndex()
                    model.refreshCloudExports()
                }
            }
            activityOwner.lifecycle.addObserver(observer)
            onDispose { activityOwner.lifecycle.removeObserver(observer) }
        }
        fun appPermissionSettings() {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
            }.onFailure { model.report("Open Android Settings → Apps → TG Photos → Permissions.") }
        }
        val archiveDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
            val entries = archiveEntries
            archiveEntries = emptyList()
            if (destination != null && entries.isNotEmpty()) {
                scope.launch {
                    try {
                        val sources = entries.map { item -> ArchiveSource(item.local?.uri ?: android.net.Uri.parse("content://${context.packageName}.private-media/media/${item.cloud!!.id}"), item.name) }
                        ArchiveCopies.save(context, sources, destination)
                        model.report("ZIP copy saved. Originals remain unchanged.")
                        model.localMedia.refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        model.report("Could not save ZIP. Choose a writable local folder. Originals remain unchanged.")
                    }
                }
            }
        }
        val allFilesSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permissionsChanged++
            if (!app.televault.data.DeviceFileAccess.granted(context)) permissionFailures++
        }
        fun requestAllFiles() {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    allFilesSettings.launch(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}")))
                } catch (_: android.content.ActivityNotFoundException) {
                    runCatching { allFilesSettings.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }.onFailure { model.report("Open Android Settings → Special app access → All files access, or select individual folders.") }
                }
            } else {
                permission.launch(permissions())
            }
        }
        LaunchedEffect(tab, galleryIntroduction) {
            if (tab == PhotoTab.LIBRARY && !galleryIntroduction && !DeviceFileAccess.granted(context)) allFilesIntroduction = true
        }
        val removeMoved = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { permissionsChanged++ }
        val localDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                if (!persistAccess(uri, true)) return@rememberLauncherForActivityResult
                val createName = pendingNewFolder
                pendingNewFolder = ""
                if (createName.isNotBlank()) {
                    scope.launch {
                        runCatching { LocalFileActions.createFolder(context, uri, createName) }.onSuccess {
                            model.addDeviceDocumentFolder(uri)
                            model.localMedia.refresh()
                        }.onFailure { model.report(it.message ?: "Could not create folder.") }
                    }
                    return@rememberLauncherForActivityResult
                }
                val items = pendingLocalMove
                pendingLocalMove = emptyList()
                scope.launch {
                    try {
                        val originals = LocalFileActions.copyOrMove(context, items.mapNotNull { it.local }, uri, name, localCopy)
                        if (originals.isNotEmpty()) {
                            val moved = items.mapNotNull { it.local }.filter { it.uri in originals }
                            val native = mutableListOf<android.net.Uri>()
                            val other = mutableListOf<LocalMedia>()
                            for (item in moved) {
                                val media = item.mime.startsWith("image/") || item.mime.startsWith("video/") || item.mime.startsWith("audio/")
                                val resolved = if (Build.VERSION.SDK_INT >= 30 && media) DeviceTrashUris.resolve(context, item) else null
                                if (resolved == null) other += item else native += resolved
                            }
                            if (other.isNotEmpty()) model.deviceFileTrash.trash(other, "library")
                            if (native.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                                removeMoved.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, native.take(500)).intentSender).build())
                                if (native.size > 500) model.report("Copies verified. First 500 originals requested; remaining originals retained.")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: LocalFileActions.PartialCopyException) {
                        model.report("${e.completedCount} copies saved; remaining copies failed. Originals were kept.")
                    } catch (_: Exception) {
                        model.report("Folder operation did not finish. Verified copies remain; check device Trash for originals already moved.")
                    }
                    permissionsChanged++
                }
            }
        }
        var pendingBackupFolder by remember { mutableStateOf<android.net.Uri?>(null) }
        var backupWifiOnly by remember { mutableStateOf(true) }
        val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                if (!persistAccess(uri, false)) return@rememberLauncherForActivityResult
                pendingBackupFolder = uri
                backupWifiOnly = true
            }
        }
        var pendingTrashFiles by remember { mutableStateOf(emptyList<android.net.Uri>()) }
        val localTrash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && pendingTrashFiles.isNotEmpty()) model.deviceTrashOrigin(pendingTrashFiles, "library")
            pendingTrashFiles = emptyList()
            permissionsChanged++
        }
        var restoringLocalTrash by remember { mutableStateOf<Set<String>>(emptySet()) }
        val restoreLocalTrash = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val ids = restoringLocalTrash
            restoringLocalTrash = emptySet()
            if (uri != null && ids.isNotEmpty()) {
                scope.launch {
                    try {
                        model.deviceFileTrash.restore(ids, uri)
                        model.localMedia.refresh()
                        model.report("Restored copies saved in the selected folder.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        model.report(e.message ?: "Restore failed. Trash copies retained.")
                    }
                }
            }
        }
        fun deleteEntries(entries: List<MediaEntry>, restore: Boolean = false) {
            if (!restore) {
                trashActions.request(entries.toList(), if (tab == PhotoTab.LIBRARY) "library" else "gallery", null)
                selected = emptySet()
                return
            }
            val remote = entries.mapNotNull { it.cloud?.id }.toSet()
            if (remote.isNotEmpty()) model.trash(remote, deleted = false)
            val stored = entries.mapNotNull { it.local?.uri?.takeIf { uri -> uri.scheme == "tgphotos-device-trash" }?.schemeSpecificPart }.toSet()
            if (stored.isNotEmpty()) {
                restoringLocalTrash = stored
                restoreLocalTrash.launch(null)
            }
            val originals = entries.mapNotNull { it.local }.filter { it.uri.scheme != "tgphotos-device-trash" }
            if (originals.isNotEmpty()) {
                scope.launch {
                    val native = mutableListOf<android.net.Uri>()
                    for (item in originals) {
                        val media = item.mime.startsWith("image/") || item.mime.startsWith("video/") || item.mime.startsWith("audio/")
                        val resolved = if (Build.VERSION.SDK_INT >= 30 && media) DeviceTrashUris.resolve(context, item) else null
                        if (resolved != null) {
                            native += resolved
                        }
                    }
                    if (native.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                        pendingTrashFiles = emptyList()
                        runCatching { localTrash.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(context.contentResolver, native.take(500), false).intentSender).build()) }.onFailure {
                            pendingTrashFiles = emptyList()
                            model.report("Android did not allow this trash request. Originals were kept.")
                        }
                        if (native.size > 500) model.report("First 500 device files requested. Select remaining files again after confirmation.")
                    }
                }
            }
            selected = emptySet()
        }
        val allAlbumLabel = label("All", "সব")
        val favoritesAlbumLabel = label("Favorites", "প্রিয়")
        val videosAlbumLabel = label("Videos", "ভিডিও")
        val projectionScope = PhotoProjectionScope(model.playbackEpoch, tab, section, if (section == "Trash") trashScope == TrashScope.DEVICE else deviceContext, showHiddenGallery, mediaFilter, trashFiles, search, folder, album, allAlbumLabel, favoritesAlbumLabel, videosAlbumLabel)
        val projectionInput = PhotoProjectionInput(state.files, local, storedTrash, state.albums, state.localFavorites, allTrashOrigins)
        val projection = rememberBackgroundProjection(projectionScope, projectionInput, PhotoProjection()) { input -> projectPhotos(input, projectionScope) }
        LaunchedEffect(tab, opening?.id, memoryStory?.key, state.files, local, people.enabled, model.playbackEpoch) {
            model.intelligenceActive(tab != PhotoTab.VAULT && opening == null && memoryStory == null)
            delay(1500)
            model.indexPeople(state.files, local)
        }
        LaunchedEffect(tab, opening?.id, memoryStory?.key, state.files, local, textIndex.enabled, model.playbackEpoch) {
            model.intelligenceActive(tab != PhotoTab.VAULT && opening == null && memoryStory == null)
            delay(2000)
            model.indexText(state.files, local)
        }
        val cloud = projection.value.cloud
        val device = projection.value.device
        val base = projection.value.base
        val visible = projection.value.visible
        val resumeDataReady by rememberUpdatedState(projection.ready && (if (deviceContext) deviceIndexReady else state.indexLoaded))
        LaunchedEffect(resumeEpoch) {
            try {
                if ((initialPosition.index > 0 || initialPosition.offset > 0) && initialPosition.explore.isBlank() && (!returnToViewedItem || initialPosition.focusId.isBlank())) {
                    val ready = withTimeoutOrNull(10_000) { snapshotFlow { resumeDataReady }.first { it } }
                    if (ready == true) {
                        // The projected rows must be measured before clamping a saved index.
                        withFrameNanos { }
                        withFrameNanos { }
                        withTimeoutOrNull(2000) { snapshotFlow { if (usesBrowseList) browseList.layoutInfo.totalItemsCount else timelineGrid.layoutInfo.totalItemsCount }.first { it > 1 || initialScrollInterrupted } }
                        val current = positionReader()
                        if (!initialScrollInterrupted && current.copy(index = 0, offset = 0, columns = 4f, focusId = "") == initialPosition.copy(index = 0, offset = 0, columns = 4f, focusId = "")) {
                            val count = if (usesBrowseList) browseList.layoutInfo.totalItemsCount else timelineGrid.layoutInfo.totalItemsCount
                            if (count > 0) {
                                val index = initialPosition.index.coerceAtMost(count - 1)
                                if (usesBrowseList) browseList.scrollToItem(index, initialPosition.offset) else timelineGrid.scrollToItem(index, initialPosition.offset)
                            }
                        }
                    }
                }
            } finally {
                initialScrollApplied = true
            }
        }
        val waitingForLibrary = visible.isEmpty() && (!projection.ready || (if (deviceContext) !deviceIndexReady || scanningFiles else !state.indexLoaded))
        val showLibraryFeedback = rememberLoadingFeedback(waitingForLibrary, projectionScope)
        val resumeAddress = positionReader().copy(index = 0, offset = 0, columns = 4f, focusId = "")
        val shortcutEligible = tab !in setOf(PhotoTab.VAULT, PhotoTab.SETTINGS) && section != "Transfers" && opening == null && !universalSearch && !showProfile && memoryStory == null && !allPeopleVisible && selected.isEmpty() && prompt.isBlank() && incomingMedia == null && !incomingBusy
        ResumeStartOffers(startHost, resumeLastLocation, resumeAddress, shortcutEligible)
        RegisterBrowseStartTarget(
            ready = { exploreMode == null && initialScrollApplied && projection.ready && browseFocus.pendingId == null && (if (deviceContext) deviceIndexReady else state.indexLoaded) },
            positioned = { if (usesBrowseList) browseList.canScrollBackward else timelineGrid.canScrollBackward },
            scrolling = { if (usesBrowseList) browseList.isScrollInProgress else timelineGrid.isScrollInProgress },
            start = { if (usesBrowseList) browseList.softScrollToStart() else timelineGrid.softScrollToStart() }
        )
        fun clearReturnFocus() {
            lastViewedId = ""
            browseFocus.request(null)
        }
        val warmIds = remember(visible) { visible.asSequence().filter { it.cloud != null && it.mime.startsWith("image/") }.take(app.televault.domain.AdaptiveMediaPolicy.WARM_COUNT).map { it.id }.toList() }
        LaunchedEffect(local, warmIds, state.indexLoaded, deviceIndexReady, tab) {
            if (state.indexLoaded && deviceIndexReady) {
                // Rebind restored originals first; speculative download must not race discovery.
                val originalsReady = local.isEmpty() || model.reconcileDeviceMedia(local)
                if (originalsReady && tab == PhotoTab.TIMELINE && section != "Trash") model.warmTimeline(warmIds)
            }
        }
        val deviceAlbums = projection.value.deviceAlbums
        val cloudAlbums = projection.value.cloudAlbums
        // Resolve selected actions from the authoritative lists, never an older painted projection.
        val selectedItems = remember(state.files, local, storedTrash, selected, projectionScope, allTrashOrigins, projection.value.duplicates, state.localFavorites) {
            if (selected.isEmpty()) {
                emptyList()
            } else {
                buildList {
                    state.files.filter { it.id in selected }.forEach { add(MediaEntry(it.id, it.name, it.mime, it.size, it.date, cloud = it)) }
                    (local.asSequence() + storedTrash.asSequence().map { it.local }).filter { it.uri.toString() in selected }.distinctBy { it.uri }.forEach { add(MediaEntry(it.uri.toString(), it.name, it.mime, it.size, it.date, local = it)) }
                }.filter { tab == PhotoTab.LIBRARY || projectionScope.allows(it, allTrashOrigins, projection.value.duplicates, state.localFavorites) }
            }
        }
        fun currentVisible(item: MediaEntry): MediaEntry? {
            if (projectionScope.epoch != model.playbackEpoch) return null
            val current = if (item.cloud != null) {
                state.files.firstOrNull { it.id == item.id }?.let { MediaEntry(it.id, it.name, it.mime, it.size, it.date, cloud = it) }
            } else {
                (local.asSequence() + storedTrash.asSequence().map { it.local }).firstOrNull { it.uri.toString() == item.id }?.let { MediaEntry(it.uri.toString(), it.name, it.mime, it.size, it.date, local = it) }
            }
            return current?.takeIf { projectionScope.allows(it, allTrashOrigins, projection.value.duplicates, state.localFavorites) }
        }
        fun selectVisible(ids: Set<String>) {
            if (projectionScope.epoch != model.playbackEpoch) return
            val requested = ids
            selected = buildSet {
                state.files.asSequence().filter { it.id in requested }.map { MediaEntry(it.id, it.name, it.mime, it.size, it.date, cloud = it) }.filter { projectionScope.allows(it, allTrashOrigins, projection.value.duplicates, state.localFavorites) }.forEach { add(it.id) }
                (local.asSequence() + storedTrash.asSequence().map { it.local }).filter { it.uri.toString() in requested }.map { MediaEntry(it.uri.toString(), it.name, it.mime, it.size, it.date, local = it) }.filter { projectionScope.allows(it, allTrashOrigins, projection.value.duplicates, state.localFavorites) }.forEach { add(it.id) }
            }
        }
        fun open(item: MediaEntry) {
            val current = currentVisible(item) ?: return
            if (current.local?.uri?.scheme == "tgphotos-device-trash") {
                model.report("Restore this encrypted device Trash copy before opening it.")
                return
            }
            viewerEntries = null
            opening = current
        }
        fun save(item: MediaEntry) {
            if (item.cloud == null) return
            exportIds = item.cloud?.let { setOf(it.id) } ?: emptySet()
            exportLocal = item.local?.let { listOf(it) } ?: emptyList()
            prompt = "export"
        }
        fun openAlbum(tile: AlbumTile) {
            if (tab == PhotoTab.GALLERY) galleryCloud = tile.cloud
            folder = tile.folder
            album = tile.album
            section = tile.section
            selected = emptySet()
            search = ""
            showSearch = false
        }
        val memoryCards = if (isPhotoTimeline && section == "All" && search.isBlank() && selected.isEmpty()) rememberPhotoMemories(visible, projectionScope, people.people, people.selfId, sceneIndex.labels, state.backedDeviceFiles) else emptyList()
        val choosePhotoSection: (String) -> Unit = { key ->
            section = key
            folder = null
            album = null
            selected = emptySet()
        }
        val mainHeader: @Composable () -> Unit = {
            Column {
                TopAppBar(title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.tg_photos_logo), label("TG Photos", "TG Photos"), Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (tab) {
                                    PhotoTab.TIMELINE -> label("Photos", "ছবি")
                                    PhotoTab.GALLERY -> label("Albums", "অ্যালবাম")
                                    PhotoTab.LIBRARY -> label("Files", "ফাইল")
                                    PhotoTab.VAULT -> label("Private Vault", "প্রাইভেট ভল্ট")
                                    PhotoTab.SETTINGS -> label("TG Photos", "TG Photos")
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }, navigationIcon = { if (tab == PhotoTab.SETTINGS) IconButton({ if (settingsCategory.isBlank()) switchTab(previousTab, false) else settingsCategory = "" }) { Icon(Icons.Outlined.ArrowBack, label("Back", "পিছনে")) } }, windowInsets = TopAppBarDefaults.windowInsets, scrollBehavior = if (isBrowse) null else headerScroll, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer), actions = {
                    if (tab !in setOf(PhotoTab.SETTINGS, PhotoTab.LIBRARY, PhotoTab.VAULT)) {
                        IconButton({
                            searchPerson = null
                            universalSearch = true
                        }) { Icon(Icons.Outlined.Search, label("Search", "খুঁজুন")) }
                    }
                    IconButton({ lockDevice() }) { Icon(Icons.Outlined.Lock, label("Lock gallery", "গ্যালারি লক")) }
                    Box {
                        IconButton({ overflow = true }) { Icon(Icons.Outlined.MoreVert, label("More", "আরও")) }
                        DropdownMenu(overflow, { overflow = false }) {
                            DropdownMenuItem(text = { Text(label("Settings", "সেটিংস")) }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = {
                                overflow = false
                                switchTab(PhotoTab.SETTINGS)
                            })
                            DropdownMenuItem(text = { Text(label("Export Cloud library", "ক্লাউড লাইব্রেরি এক্সপোর্ট")) }, leadingIcon = { Icon(Icons.Outlined.FolderZip, null) }, onClick = {
                                overflow = false
                                switchTab(PhotoTab.SETTINGS)
                                settingsCategory = "Export Cloud library"
                            })
                            DropdownMenuItem(text = { Text(label("Private profile", "ব্যক্তিগত প্রোফাইল")) }, leadingIcon = { Icon(Icons.Outlined.AccountCircle, null) }, onClick = {
                                overflow = false
                                showProfile = true
                            })
                            DropdownMenuItem(text = { Text(label("Sync cloud", "ক্লাউড সিঙ্ক")) }, leadingIcon = { Icon(Icons.Outlined.Sync, null) }, onClick = {
                                overflow = false
                                model.sync()
                            })
                            if (tab == PhotoTab.GALLERY) {
                                DropdownMenuItem(text = { Text(label("Media access", "মিডিয়ার অনুমতি")) }, onClick = {
                                    overflow = false
                                    permission.launch(permissions())
                                })
                                DropdownMenuItem(text = { Text(label("Refresh device", "ডিভাইস রিফ্রেশ")) }, onClick = {
                                    overflow = false
                                    permissionsChanged++
                                })
                            }
                            listOf("Albums", "Folders", "Trash", "Transfers").forEach { key ->
                                DropdownMenuItem(text = { Text(sectionLabel(key)) }, onClick = {
                                    overflow = false
                                    galleryCloud = false
                                    tab = if (key == "Transfers") {
                                        PhotoTab.LIBRARY
                                    } else if (key == "Trash") {
                                        PhotoTab.GALLERY
                                    } else {
                                        PhotoTab.TIMELINE
                                    }
                                    section = key
                                    selected = emptySet()
                                    if (key == "Trash") {
                                        trashScope = TrashScope.DEVICE
                                        trashFiles = false
                                    }
                                    folder = null
                                    album = null
                                    libraryDevice = false
                                })
                            }
                        }
                    }
                })
                Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal = 16.dp)) { HeaderActivity(model) }
                UsageGuidance(model, "tab-navigation", rootBackPresses, "Use Photos, Albums, Files or Vault below to change sections. Back stays in this section.", "সেকশন বদলাতে নিচের Photos, Albums, Files বা Vault বোতামে ট্যাপ করুন। Back এই সেকশনেই রাখে।", Modifier.padding(horizontal = 16.dp))
                UsageGuidance(model, "device-permissions", permissionFailures, "Open App permissions or Device file access and allow the category you want to browse. Cloud files need no device-file permission.", "App permissions বা Device file access খুলে প্রয়োজনীয় অনুমতি দিন। ক্লাউড ফাইলের জন্য ফোনের ফাইলের অনুমতি লাগে না।", Modifier.padding(horizontal = 16.dp))
            }
        }
        val commonIntroduction: @Composable () -> Unit = {
            if (state.notice.isNotBlank()) AppSurface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp)) { Text(state.notice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            AnimatedVisibility(headerScroll.state.collapsedFraction < .5f && (folder != null || album != null || section in setOf("Trash", "Transfers", "Folders")), Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            folder?.substringAfterLast('/') ?: album?.let { id -> state.albums.find { it.id == id }?.name } ?: when (tab) {
                                PhotoTab.TIMELINE -> if (section in setOf("Albums", "Folders")) sectionLabel(section) else label("Photos", "ছবি")
                                PhotoTab.GALLERY -> if (section == "Trash") sectionLabel("Trash") else label("Albums", "অ্যালবাম")
                                PhotoTab.LIBRARY -> label("Files", "ফাইল")
                                PhotoTab.VAULT -> label("Private Vault", "প্রাইভেট ভল্ট")
                                PhotoTab.SETTINGS -> label("Settings", "সেটিংস")
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (tab !in setOf(PhotoTab.SETTINGS, PhotoTab.VAULT)) Text(if ((section == "Trash" && trashScope == TrashScope.DEVICE) || (section != "Trash" && deviceContext)) label("On this device", "এই ডিভাইসে") else label("Private, encrypted cloud", "প্রাইভেট এনক্রিপ্টেড ক্লাউড"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tab == PhotoTab.GALLERY && section != "Albums" || folder != null || album != null) {
                        IconButton({
                            folder = null
                            album = null
                            section = if (tab == PhotoTab.GALLERY) {
                                "Albums"
                            } else if (tab == PhotoTab.LIBRARY) {
                                section
                            } else {
                                "All"
                            }
                        }) { Icon(Icons.Outlined.ArrowBack, label("Back to albums", "অ্যালবামে ফিরুন")) }
                    }
                    if (tab == PhotoTab.TIMELINE && folder == null && album == null && selected.isEmpty()) {
                        AppSurface(Modifier.clickable { backupStatusVisible = !backupStatusVisible }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (state.busy) Icons.Outlined.Sync else Icons.Outlined.CloudDone, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.width(5.dp))
                                Text(label("Cloud", "ক্লাউড"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(!isPhotoTimeline && backupStatusVisible && tab == PhotoTab.TIMELINE) {
                BackupStatusCard(model, { model.sync() }, { backupStatusVisible = false }, enable = { prompt = "camera" }, manage = {
                    switchTab(PhotoTab.SETTINGS)
                    settingsCategory = "Backup & Sync"
                })
            }
            AnimatedVisibility(showSearch && tab != PhotoTab.LIBRARY) { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), placeholder = { Text(label("Search photos, folders or files", "ছবি, ফোল্ডার বা ফাইল খুঁজুন")) }, singleLine = true, shape = RoundedCornerShape(28.dp), leadingIcon = { Icon(Icons.Outlined.Search, null) }) }
        }
        val browsingIntroduction: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                mainHeader()
                commonIntroduction()
                BrowseOptions(
                    summary = "${visible.size} · ${sectionLabel(section)}",
                    active = mediaFilters.options.category != app.televault.domain.MediaCategories.Category.ALL || mediaFilters.options.clean || mediaFilters.options.hidden != 0,
                    reveal = backupStatusVisible,
                    dismissed = { backupStatusVisible = false }
                ) {
                    if (isPhotoTimeline) {
                        if (backupStatusVisible) {
                            BackupStatusCard(model, { model.sync() }, { backupStatusVisible = false }, enable = { prompt = "camera" }, manage = {
                                switchTab(PhotoTab.SETTINGS)
                                settingsCategory = "Backup & Sync"
                            })
                        } else {
                            CloudStorageCard(state, compact = true, onClick = { backupStatusVisible = true }, minimal = true)
                        }
                        MediaFilterBar(mediaFilters, Modifier.padding(top = 8.dp))
                        PhotoSectionPills(section, choosePhotoSection, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        TimelineControls(density, { density = it }, visible.size, mosaicLayout, { enabled ->
                            scope.launch { model.preferences.setMosaicLayout(enabled) }
                            Unit
                        }, compact = true)
                        if (section == "All") {
                            PhotoMemories(memoryCards, model) { card ->
                                memoryStory = card
                            }
                        }
                    } else if (!fileLibrary && tab != PhotoTab.VAULT) {
                        if (tab in setOf(PhotoTab.TIMELINE, PhotoTab.GALLERY) && section !in setOf("Trash", "Transfers")) MediaFilterBar(mediaFilters)
                        if (tab != PhotoTab.LIBRARY && section !in setOf("Albums", "Folders", "Trash", "Transfers")) {
                            PhotoSectionPills(section, { key ->
                                section = key
                                selected = emptySet()
                            }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        } else if (tab == PhotoTab.LIBRARY) {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (if (tab == PhotoTab.LIBRARY) listOf("All files", "Documents", "Audio", "Transfers") else listOf("All", "Photos", "Videos", "Favorites")).forEach { key ->
                                    FilterChip(section == key, {
                                        section = key
                                        if (tab == PhotoTab.LIBRARY) {
                                            folder = null
                                            album = null
                                        }
                                        if (key == "Trash") {
                                            trashScope = TrashScope.DEVICE
                                            trashFiles = false
                                        }
                                        selected = emptySet()
                                    }, label = { Text(sectionLabel(key)) }, shape = RoundedCornerShape(18.dp))
                                }
                            }
                        }
                        if (tab == PhotoTab.LIBRARY && section in setOf("Audio", "Documents", "All files")) {
                            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(!libraryDevice, {
                                    libraryDevice = false
                                    folder = null
                                    selected = emptySet()
                                }, label = { Text(label("Cloud", "ক্লাউড")) })
                                Spacer(Modifier.width(8.dp))
                                FilterChip(libraryDevice, {
                                    libraryDevice = true
                                    folder = null
                                    selected = emptySet()
                                }, label = { Text(label("On device", "ফোনে")) })
                            }
                        }
                        if (tab == PhotoTab.LIBRARY && libraryDevice && section in setOf("Audio", "Documents", "All files")) {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(libraryFolders, {
                                    libraryFolders = true
                                    folder = null
                                    selected = emptySet()
                                }, label = { Text(label("Folders", "ফোল্ডার")) })
                                FilterChip(!libraryFolders, {
                                    libraryFolders = false
                                    folder = null
                                    selected = emptySet()
                                }, label = { Text(label("View all", "সব দেখুন")) })
                                TextButton({ documentFolderPicker.launch(null) }, Modifier.heightIn(min = 48.dp)) {
                                    Icon(Icons.Outlined.CreateNewFolder, null)
                                    Text(label("Add device folder", "ফোনের ফোল্ডার যোগ করুন"))
                                }
                                TextButton({ permissionsChanged++ }) { Text(label("Refresh", "রিফ্রেশ")) }
                            }
                            if (section != "Audio") {
                                FilledTonalButton({ if (DeviceFileAccess.granted(context)) permissionsChanged++ else allFilesIntroduction = true }, Modifier.padding(horizontal = 20.dp)) {
                                    Icon(Icons.Outlined.FolderOpen, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (DeviceFileAccess.granted(context)) label("Shared storage access enabled · refresh", "স্টোরেজের অনুমতি আছে · রিফ্রেশ") else label("Allow all shared files", "ফোনের সব shared ফাইলের অনুমতি দিন"))
                                }
                            }
                            Text(label("Local only. All-files access shows readable shared storage; private app storage is protected. No automatic cloud upload.", "শুধু ফোনে দেখা। অনুমতি দিলে shared storage-এর সব পাঠযোগ্য ফাইল দেখাবে; অন্য অ্যাপের private storage সুরক্ষিত। স্বয়ংক্রিয় আপলোড হবে না।"), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                            if (section == "Audio") {
                                Row(Modifier.padding(horizontal = 20.dp)) {
                                    TextButton({ permission.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO) else permissions()) }) { Text(label("Allow audio access", "অডিও দেখার অনুমতি দিন")) }
                                    TextButton(::appPermissionSettings) { Text(label("App permissions", "অ্যাপের অনুমতি")) }
                                }
                            }
                            if (scanningFiles) {
                                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label("Scanning device files", "ফোনের ফাইল স্ক্যান হচ্ছে") + " · $scannedFiles", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (tab == PhotoTab.GALLERY && folder != null) {
                            val path = folder!!
                            val subscriptions = state.backups.filter { it.deviceFolder == path || (it.id.startsWith("camera:") && app.televault.domain.DeviceBackupFolder.matches(path, "", true)) }
                            AppSurface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).animateContentSize(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)) {
                                ListItem(headlineContent = { Text(label("Back up this folder", "এই ফোল্ডার ব্যাকআপ করুন")) }, supportingContent = { Text(if (subscriptions.isNotEmpty()) label("New photos and videos are encrypted automatically", "নতুন ছবি ও ভিডিও স্বয়ংক্রিয়ভাবে এনক্রিপ্ট হবে") else label("Off · existing cloud backups are kept", "বন্ধ · পুরোনো ক্লাউড ব্যাকআপ থাকবে"), style = MaterialTheme.typography.bodySmall) }, leadingContent = { Icon(Icons.Outlined.CloudUpload, null) }, trailingContent = {
                                    Switch(subscriptions.isNotEmpty(), { enabled ->
                                        if (enabled) {
                                            model.deviceFolderBackup(path, true)
                                            notifyAccess()
                                        } else {
                                            subscriptions.forEach { model.stopBackup(it.id) }
                                        }
                                    })
                                }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                            }
                        }
                        if (section in setOf("Albums", "Folders")) {
                            if (tab == PhotoTab.GALLERY) {
                                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton({ permission.launch(permissions()) }) {
                                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(label("Media access", "মিডিয়ার অনুমতি"))
                                    }
                                    TextButton(::appPermissionSettings) { Text(label("App permissions", "অ্যাপের অনুমতি")) }
                                    TextButton({
                                        section = "Trash"
                                        trashScope = TrashScope.DEVICE
                                        trashFiles = false
                                        selected = emptySet()
                                    }) {
                                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                                        Text(label("Trash", "ট্র্যাশ"))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton({ permissionsChanged++ }) { Text(label("Refresh", "রিফ্রেশ")) }
                                }
                            }
                        }
                        if (section == "Trash") {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                TrashScope.entries.forEachIndexed { index, choice ->
                                    SegmentedButton(selected = trashScope == choice, onClick = {
                                        trashScope = choice
                                        selected = emptySet()
                                    }, shape = SegmentedButtonDefaults.itemShape(index, TrashScope.entries.size), label = {
                                        Text(
                                            when (choice) {
                                                TrashScope.CLOUD -> label("Cloud", "ক্লাউড")
                                                TrashScope.DEVICE -> label("On device", "ফোনে")
                                            }
                                        )
                                    })
                                }
                            }
                            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!trashFiles, {
                                    trashFiles = false
                                    selected = emptySet()
                                }, label = { Text(label("Photos / videos", "ছবি / ভিডিও")) })
                                FilterChip(trashFiles, {
                                    trashFiles = true
                                    selected = emptySet()
                                }, label = { Text(if (trashScope == TrashScope.CLOUD) label("Cloud files", "ক্লাউড ফাইল") else label("Device files", "ফোনের ফাইল")) })
                            }
                            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label("Cloud: retained until deleted · Device: Android retention", "ক্লাউড: আপনি মোছা পর্যন্ত · ফোন: Android-এর সময়সীমা"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton({ permanentDelete = visible.toList() }, enabled = visible.isNotEmpty()) { Text(label("Empty", "খালি")) }
                                TextButton({ deleteEntries(selectedItems, true) }, enabled = selected.isNotEmpty()) { Text(label("Restore", "ফিরিয়ে আনুন")) }
                            }
                            TextButton({ permission.launch(permissions()) }) { Text(label("Allow device trash access", "ফোনের ট্র্যাশ দেখার অনুমতি দিন")) }
                        }
                        if (section !in setOf("Albums", "Folders")) {
                            TimelineControls(
                                density,
                                { density = it },
                                visible.size,
                                mosaicLayout,
                                if (section in setOf("Audio", "Documents", "All files")) {
                                    null
                                } else {
                                    { enabled ->
                                        scope.launch { model.preferences.setMosaicLayout(enabled) }
                                        Unit
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        Scaffold(
            modifier = Modifier.pointerInput(edgeReveal, tab, selected.isEmpty()) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    edgeReveal.beginGesture(isPhotoTimeline && selected.isEmpty() && !timelineGrid.canScrollBackward)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    edgeReveal.endGesture()
                }
            }.nestedScroll(statusPull),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if (isBrowse) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = { if (!isBrowse) mainHeader() },

            bottomBar = {
                if (selected.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        AppSurface(color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .25f)), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(60.dp)) {
                            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ selected = emptySet() }) { Icon(Icons.Outlined.Close, label("Clear selection", "নির্বাচন বাতিল")) }
                                Text("${selected.size}", Modifier.padding(end = 8.dp), fontWeight = FontWeight.SemiBold)
                                Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                    SelectionAction(Icons.Outlined.Share, label("Share", "শেয়ার")) {
                                        runCatching { shareEntries(context, model, selectedItems.toList()) }.onFailure { model.report(it.message ?: "Could not share these files.") }
                                    }
                                    if (section != "Trash") {
                                        SelectionAction(Icons.Outlined.DeleteOutline, label("Trash", "ট্র্যাশ")) { deleteEntries(selectedItems.toList()) }
                                        SelectionAction(Icons.Outlined.DriveFileMove, label("Move", "সরান")) {
                                            prompt = "move"
                                            name = ""
                                        }
                                        SelectionAction(Icons.Outlined.ContentCopy, label("Copy", "কপি")) {
                                            prompt = "copy"
                                            name = ""
                                        }
                                    }
                                    TextButton({ if (tab == PhotoTab.LIBRARY && section !in setOf("Trash", "Transfers")) selected = libraryShownIds else selectVisible(visible.map { it.id }.toSet()) }) { Text(label("All", "সব")) }
                                    if (section != "Trash" && selectedItems.any { it.local != null }) {
                                        Box {
                                            IconButton({ backupSelectionMenu = true }) { Icon(Icons.Outlined.CloudUpload, label("Backup selected", "নির্বাচিত ব্যাকআপ")) }
                                            DropdownMenu(backupSelectionMenu, { backupSelectionMenu = false }) {
                                                DropdownMenuItem(text = { Text(if (selectedItems.size > 1) label("Backup all", "সব ব্যাকআপ করুন") else label("Backup", "ব্যাকআপ")) }, onClick = {
                                                    backupSelectionMenu = false
                                                    model.upload(selectedItems.mapNotNull { it.local?.uri })
                                                    selected = emptySet()
                                                    notifyAccess()
                                                })
                                                DropdownMenuItem(text = { Text(label("Upload another copy", "আরেকটি কপি আপলোড করুন")) }, onClick = {
                                                    backupSelectionMenu = false
                                                    model.upload(selectedItems.mapNotNull { it.local?.uri }, forceCopy = true)
                                                    selected = emptySet()
                                                })
                                                if (selectedItems.any { it.local != null }) {
                                                    DropdownMenuItem(text = { Text(if (selectedItems.size > 1) label("Backup all and delete from device", "সব ব্যাকআপ করে ডিভাইস থেকে সরান") else label("Backup and delete from device", "ব্যাকআপ করে ডিভাইস থেকে সরান")) }, onClick = {
                                                        backupSelectionMenu = false
                                                        model.backupAndTrash(selectedItems.mapNotNull { it.local }, if (tab == PhotoTab.LIBRARY) "library" else "gallery")
                                                        selected = emptySet()
                                                        notifyAccess()
                                                    })
                                                }
                                            }
                                        }
                                    }
                                    if (section != "Trash" && selectedItems.any { it.cloud != null }) {
                                        IconButton({
                                            trashActions.request(selectedItems.toList(), if (tab == PhotoTab.LIBRARY) "library" else "gallery", app.televault.domain.TrashChoice.Scope.DEVICE)
                                            selected = emptySet()
                                        }) { Icon(Icons.Outlined.AutoDelete, label("Remove backed-up device copies", "ব্যাকআপ হওয়া ডিভাইস কপি সরান")) }
                                    }
                                    if (section != "Trash" && selectedItems.all { it.cloud != null }) {
                                        IconButton({
                                            model.pinOffline(selectedItems.map { it.id }.toSet())
                                            showOfflineDownloads = true
                                            selected = emptySet()
                                        }) { Icon(Icons.Outlined.OfflinePin, label("Keep selected offline", "নির্বাচিতগুলি অফলাইন রাখুন")) }
                                    }
                                    if (selectedItems.all { it.cloud != null }) {
                                        IconButton({
                                            exportIds = selectedItems.mapNotNull { it.cloud?.id }.toSet()
                                            exportLocal = emptyList()
                                            prompt = "export"
                                        }) { Icon(Icons.Outlined.Download, label("Download selected", "নির্বাচিত ডাউনলোড")) }
                                    }
                                    if (section != "Trash") {
                                        IconButton({
                                            pendingVaultCopies = selectedItems.map { it.local?.uri ?: android.net.Uri.parse("content://${context.packageName}.private-media/media/${it.cloud!!.id}") }
                                            switchTab(PhotoTab.VAULT)
                                        }) { Icon(Icons.Outlined.Lock, label("Move selected to Private Vault", "নির্বাচিতগুলি প্রাইভেট ভল্টে সরান")) }
                                        IconButton({ selectedItems.forEach { if (it.cloud != null) model.favorite(it.id, !it.cloud.favorite) else model.localFavorite(it.id, it.id !in state.localFavorites) } }) { Icon(Icons.Outlined.FavoriteBorder, label("Favorites", "প্রিয়")) }
                                        if (selectedItems.any { it.cloud?.mime?.startsWith("image/") == true }) {
                                            IconButton({
                                                prompt = "album"
                                                name = ""
                                            }) { Icon(Icons.Outlined.PhotoAlbum, label("Add cloud album", "ক্লাউড অ্যালবাম")) }
                                        }
                                    }
                                    if (section == "Trash") {
                                        IconButton({ deleteEntries(selectedItems, true) }) { Icon(Icons.Outlined.Restore, label("Restore", "ফিরিয়ে আনুন")) }
                                        IconButton({ permanentDelete = selectedItems.toList() }) { Icon(Icons.Outlined.DeleteForever, label("Delete permanently", "স্থায়ীভাবে মুছুন")) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    GlassNavigation(
                        listOf(
                            GlassTab(label("Photos", "ছবি"), Icons.Outlined.CloudQueue),
                            GlassTab(label("Albums", "অ্যালবাম"), Icons.Outlined.PhotoLibrary),
                            GlassTab(label("Files", "ফাইল"), Icons.Outlined.FolderOpen),
                            GlassTab(label("Vault", "ভল্ট"), Icons.Outlined.Lock)
                        ),
                        tab.ordinal,
                        search = {
                            searchPerson = null
                            universalSearch = true
                        }
                    ) { index -> switchTab(PhotoTab.entries[index]) }
                }
            },
            floatingActionButton = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ResumeStartButton(resumeAddress, shortcutEligible && exploreMode == null, ::clearReturnFocus)
                    if (tab == PhotoTab.TIMELINE && selected.isEmpty()) {
                        PhotoExploreButton(model.preferences) { action ->
                            when (action) {
                                "Upload" -> upload.launch(arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*"))
                                "Profile" -> showProfile = true
                                "All people" -> allPeopleVisible = true
                                "Shorts" -> pendingShorts = true
                                else -> exploreMode = action
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.padding(if (isBrowse) PaddingValues() else padding).consumeWindowInsets(padding).fillMaxSize().pageEntrance(tab, section, folder, album, animateInitial = false)
                ) {
                    if (!isBrowse) commonIntroduction()
                    if (tab == PhotoTab.VAULT) {
                        PrivateVaultScreen(model, Modifier.weight(1f), pendingVaultCopies, leadingContent = browsingIntroduction) { pendingVaultCopies = emptyList() }
                    } else if (tab == PhotoTab.SETTINGS) {
                        if (settingsCategory == "Export Cloud library") {
                            CloudExportSettings(model, Modifier.weight(1f))
                        } else if (settingsCategory.isBlank()) {
                            ForgottenVaultManager()
                            SettingsOverview({ settingsCategory = it }, { prompt = "logout" }, Modifier.weight(1f))
                        } else {
                            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                item { TelegramStorageWarning() }
                                item { Text(settingsCategory, style = MaterialTheme.typography.titleLarge) }
                                if (settingsCategory == "People & text search") {
                                    item {
                                        PeopleAlbums(people, model) { person ->
                                            searchPerson = person.id
                                            universalSearch = true
                                        }
                                    }
                                    item {
                                        val analysisStatus by app.televault.data.MediaIntelligenceScheduling.status.collectAsStateWithLifecycle()
                                        if (analysisStatus.isNotBlank()) Text(analysisStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    item { FaceResetControls(model) }
                                    item { TextSearchControls(model) }
                                    item { Text("Automatic indexing continues in the background while Photos is unlocked. It pauses on lock or battery saver and resumes after unlock. Cloud originals wait for an unmetered connection. Private Vault is excluded.", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (settingsCategory == "About") item { Text("TG Photos ${app.televault.BuildConfig.VERSION_NAME}\nNative Android gallery. Encrypted Telegram backups. Independent Private Vault. Keep passwords and recovery keys safe.", style = MaterialTheme.typography.bodyMedium) }
                                if (settingsCategory == "Player") item { Text("Set playback speed, audio language, subtitles and fit/fill from the video player's menu. Choose animation duration below.", style = MaterialTheme.typography.bodyMedium) }
                                if (settingsCategory in setOf("Help")) {
                                    item { TextButton({ savePreference { model.preferences.setFeatureIntroductionSeen(false) } }) { Text(label("Review app introduction", "অ্যাপের পরিচিতি আবার দেখুন")) } }
                                    item {
                                        AppSurface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Column(Modifier.padding(20.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(label("Show hidden Gallery media", "গ্যালারির লুকানো মিডিয়া দেখান"), Modifier.weight(1f))
                                                    Switch(showHiddenGallery, { scope.launch { model.preferences.setShowHiddenGallery(it) } })
                                                }
                                                Text(label("Shows small icons, uncommon image formats and hidden folders. Files and originals are never deleted by this filter.", "ছোট আইকন, অন্য ছবি ফরম্যাট ও লুকানো ফোল্ডার দেখায়। এই ফিল্টার কোনও ফাইল মুছে দেয় না।"), style = MaterialTheme.typography.bodySmall)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(label("Helpful gesture guides", "ব্যবহারের সহায়ক নির্দেশনা"), Modifier.weight(1f))
                                                    Switch(guidanceEnabled, { scope.launch { model.preferences.setGuidanceEnabled(it) } })
                                                }
                                                Text(label("After repeated unsupported actions, a short hand guide appears at most three times per topic for this Drive.", "বারবার অসমর্থিত কাজ করলে এই Drive-এ প্রতিটি বিষয়ের হাতের নির্দেশনা সর্বোচ্চ তিনবার দেখায়।"), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Storage & Cache")) {
                                    item { CloudStorageCard(state) }
                                    item { MediaStorageCard(model) }
                                }
                                if (settingsCategory in setOf("Security")) {
                                    item { TextButton({ telegramSessions = true }) { Text(label("Telegram devices / other sessions", "Telegram ডিভাইস / অন্য সেশন")) } }
                                }
                                if (settingsCategory in setOf("Security")) {
                                    item {
                                        TextButton({
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://passwords.google.com"))) }
                                                .onFailure { model.report("Could not open Password Manager. Open it from your phone's settings.") }
                                        }) { Text(label("Open Google Password Manager", "Google Password Manager খুলুন")) }
                                    }
                                }
                                if (settingsCategory in setOf("Security", "Storage & Cache")) {
                                    item {
                                        AppSurface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                            Column(Modifier.padding(22.dp)) {
                                                Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                                Spacer(Modifier.height(12.dp))
                                                Text(label("Privacy, by design", "গোপনীয়তাই প্রথম"), style = MaterialTheme.typography.titleLarge)
                                                Text(label("Your recovery password protects this vault. Phone security gives you quick access.", "রিকভারি পাসওয়ার্ড ভল্টকে সুরক্ষিত রাখে। ফোনের নিরাপত্তা দিয়ে দ্রুত খুলতে পারবেন।"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                                                Spacer(Modifier.height(12.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp))
                                                    Text(label("Encrypted files & cache · locks when screen turns off", "ফাইল ও ক্যাশ এনক্রিপ্টেড · স্ক্রিন বন্ধ হলেই লক"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                }
                                                Text(label("Share and Download create access outside this vault. Only choose apps you trust. Screen captures remain allowed in your gallery.", "Share ও Download করলে ভল্টের বাইরে প্রবেশাধিকার যায়। বিশ্বস্ত অ্যাপ বেছে নিন। গ্যালারিতে স্ক্রিনশট নেওয়া যায়।"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                                                if (app.televault.BuildConfig.DEBUG) Text(label("Developer build: debugging is enabled. Use the hardened APK for normal use.", "ডেভেলপার বিল্ড: debugging চালু। সাধারণ ব্যবহারে hardened APK ব্যবহার করুন।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                                if (state.patternRecoveryAvailable) Text(label("Old recovery credentials detected. Set a strong password to remove pattern recovery from this Drive.", "পুরোনো রিকভারি পাওয়া গেছে। শক্ত পাসওয়ার্ড সেট করে এই Drive থেকে প্যাটার্ন সরান।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                                TextButton({ authenticate { model.rotateDeviceKey() } }) { Text(label("Rotate this phone's key protection", "এই ফোনের কী সুরক্ষা বদলান")) }
                                                TextButton({ showOfflineDownloads = true }) { Text(label("Encrypted offline downloads", "এনক্রিপ্টেড অফলাইন ডাউনলোড")) }
                                                TextButton({ authenticate { changePassword = true } }) {
                                                    Icon(Icons.Outlined.LockReset, null)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(label("Change recovery password", "রিকভারি পাসওয়ার্ড বদলান"))
                                                }
                                            }
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Security")) {
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text("App lock", style = MaterialTheme.typography.titleMedium)
                                                Text("Choose when the main gallery asks for verification after you leave. Daily mode verifies once per calendar day. Off removes the automatic app prompt; phone security and encryption stay enabled.", style = MaterialTheme.typography.bodySmall)
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    listOf(0 to "Off", 2 to "2 minutes", 5 to "5 minutes", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour", 120 to "2 hours", 1440 to "Once a day").forEach { (minutes, title) ->
                                                        FilterChip(selected = lockGraceMinutes == minutes, onClick = { savePreference { changeLockGraceMinutes(minutes) } }, label = { Text(title) })
                                                    }
                                                }
                                                Text("Private Vault always keeps its separate lock. Manual lock and sensitive actions still require verification. Restarting the phone requires verification for timed and daily modes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Backup & Sync", "Data Usage")) {
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Column {
                                                ListItem(headlineContent = { Text(label("Data Saver", "ডেটা সেভার")) }, supportingContent = { Text(label("Only new Camera photos back up automatically. Other files need manual Upload. Streaming cache and photo prefetch are off.", "শুধু নতুন Camera ছবি অটো ব্যাকআপ হবে। অন্য ফাইল ম্যানুয়ালি আপলোড করুন। স্ট্রিমিং ক্যাশ ও প্রি-ফেচ বন্ধ থাকবে।")) }, trailingContent = {
                                                    Switch(state.uploadSettings.dataSaver, {
                                                        if (it) {
                                                            enableSaverAfterPermission = true
                                                            enableCameraAfterPermission = true
                                                            permission.launch(permissions())
                                                        } else {
                                                            model.dataSaver(false)
                                                        }
                                                    })
                                                }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                                                ListItem(headlineContent = { Text(label("Camera auto backup", "ক্যামেরা অটো ব্যাকআপ")) }, supportingContent = { Text(label("Photos first, then camera videos up to your chosen length. Wi-Fi has no daily data cap.", "আগে ছবি, তারপর নির্ধারিত দৈর্ঘ্যের ক্যামেরা ভিডিও। Wi-Fi-তে দৈনিক ডেটা সীমা নেই।")) }, trailingContent = { Switch(state.uploadSettings.cameraBackup, { if (it) prompt = "camera" else model.settings(state.uploadSettings.photoLimitMb, state.uploadSettings.mobileVideos, false) }) }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                                                ListItem(headlineContent = { Text(label("Videos on mobile data", "মোবাইল ডেটায় ভিডিও")) }, supportingContent = { Text(if (state.uploadSettings.unlimited) label("Included in Full backup. Switch back to limited data in Settings → Data Usage before changing this choice.", "Full backup-এ এটি চালু আছে। বদলাতে আগে Settings → Data Usage থেকে সীমিত ডেটায় ফিরুন।") else label("On allows videos of any length without the daily mobile allowance. Off uses limited Camera video backup; other videos wait for Wi-Fi. Automatic video notices remain.", "চালু করলে দৈর্ঘ্য বা দৈনিক মোবাইল ডেটা সীমা ছাড়া ভিডিও আপলোড হবে। বন্ধে ক্যামেরার সীমিত ভিডিও ব্যাকআপ হবে, অন্য ভিডিও Wi-Fi-এর অপেক্ষায় থাকবে। অটো ভিডিওর নোটিস থাকবে।")) }, trailingContent = {
                                                    Switch(state.uploadSettings.mobileVideos || state.uploadSettings.unlimited, {
                                                        if (it) videoDataConfirmation = true else model.settings(state.uploadSettings.photoLimitMb, false, state.uploadSettings.cameraBackup)
                                                    }, enabled = !state.uploadSettings.unlimited)
                                                }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                                                if (state.transfers.any { it.phase == "VIDEO_NOTICE_REQUIRED" }) {
                                                    Text(label("Automatic video backup is waiting for its notification. Enable notifications or acknowledge the video in Library → Transfers.", "অটো ভিডিও ব্যাকআপ জানানোর অনুমতির অপেক্ষায়। নোটিফিকেশন চালু করুন, অথবা Library → Transfers-এ ভিডিওর অনুমতি দিন।"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                                                    TextButton({
                                                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                                                            .onFailure { model.report("Open Android Settings → Apps → TG Photos → Notifications.") }
                                                    }) { Text(label("Video backup notifications", "ভিডিও ব্যাকআপ নোটিফিকেশন")) }
                                                }
                                                TextButton({ prompt = "folder-backup" }, Modifier.padding(horizontal = 8.dp)) {
                                                    Icon(Icons.Outlined.CreateNewFolder, null)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(label("Add backup folder", "ব্যাকআপ ফোল্ডার যোগ"))
                                                }
                                            }
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Data Usage")) {
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(label("Automatic backup allowance", "অটো ব্যাকআপের ডেটা সীমা"), style = MaterialTheme.typography.titleMedium)
                                                Text(label("Mobile data: 250 MB/day by default, photos first, then Camera videos up to 2 minutes. Manual Upload has no app data or duration limit. Wi-Fi has no daily or video-length cap. Existing saved choices are preserved.", "মোবাইল ডেটায় ডিফল্ট দৈনিক ২৫০ MB, আগে ছবি তারপর ২ মিনিট পর্যন্ত ক্যামেরা ভিডিও। Manual Upload-এ অ্যাপের ডেটা বা দৈর্ঘ্যের সীমা নেই। Wi-Fi-তে দৈনিক বা ভিডিওর দৈর্ঘ্যের সীমা নেই। আগের সেভ করা পছন্দ থাকবে।"), style = MaterialTheme.typography.bodySmall)
                                                OutlinedTextField(mobileLimit, {
                                                    mobileLimit = it.filter(Char::isDigit).take(6)
                                                    backupSaved = false
                                                }, label = { Text(label("Daily limit (MB)", "দৈনিক সীমা (MB)")) }, supportingText = { Text("1–100000 MB") }, isError = mobileLimit.toLongOrNull() !in 1L..100000L, enabled = !backupSaving && !state.uploadSettings.unlimited, singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
                                                Text(label("Camera videos on mobile data", "মোবাইল ডেটায় ক্যামেরা ভিডিও"), style = MaterialTheme.typography.titleSmall)
                                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    listOf(1, 2, 5, 10, 0).forEach { minutes ->
                                                        FilterChip(backupVideoMinutes == minutes, {
                                                            backupVideoMinutes = minutes
                                                            backupSaved = false
                                                        }, enabled = !backupSaving && !state.uploadSettings.unlimited && !state.uploadSettings.mobileVideos, label = { Text(if (minutes == 0) label("Any length", "সব দৈর্ঘ্য") else "$minutes min") })
                                                    }
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(label("Photos before automatic videos", "অটো ভিডিওর আগে ছবি"), Modifier.weight(1f))
                                                    Switch(backupPhotosFirst, {
                                                        backupPhotosFirst = it
                                                        backupSaved = false
                                                    }, enabled = !backupSaving)
                                                }
                                                Text(label("Used today: ", "আজ ব্যবহৃত: ") + sizeLabel(state.uploadSettings.usedBytes) + if (state.uploadSettings.unlimited) label(" · No limit", " · সীমা নেই") else " / " + sizeLabel(state.uploadSettings.dailyLimitBytes), style = MaterialTheme.typography.bodySmall)
                                                if (state.uploadSettings.extraTodayMb > 0) Text(label("Extra today: ", "আজ অতিরিক্ত: ") + "${state.uploadSettings.extraTodayMb} MB", style = MaterialTheme.typography.bodySmall)
                                                Button({
                                                    val limit = mobileLimit.toLongOrNull() ?: return@Button
                                                    backupSaving = true
                                                    scope.launch {
                                                        try {
                                                            model.saveBackupOptions(limit, backupVideoMinutes, backupPhotosFirst)
                                                            backupSaved = true
                                                        } catch (cancel: CancellationException) {
                                                            throw cancel
                                                        } catch (_: Exception) {
                                                            model.report("Backup settings could not be saved. Try again.")
                                                        } finally {
                                                            backupSaving = false
                                                        }
                                                    }
                                                }, enabled = !backupSaving && mobileLimit.toLongOrNull() in 1L..100000L) {
                                                    Text(
                                                        if (backupSaving) {
                                                            label("Saving…", "সেভ হচ্ছে…")
                                                        } else if (backupSaved) {
                                                            label("Saved ✓", "সেভ হয়েছে ✓")
                                                        } else {
                                                            label("Save backup settings", "ব্যাকআপ সেটিংস সেভ করুন")
                                                        }
                                                    )
                                                }
                                                androidx.compose.animation.AnimatedVisibility(backupSaved) { Text(label("Saved on this phone. Your choices will stay after restarting the app.", "এই ফোনে সেভ হয়েছে। অ্যাপ আবার খুললেও আপনার পছন্দ থাকবে।"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                                                TextButton({ savePreference { model.overrideLimit() } }, enabled = !backupSaving && !state.uploadSettings.unlimited) { Text(label("Allow extra today · +200 MB", "আজ অতিরিক্ত · +২০০ MB")) }
                                                OutlinedButton({ if (state.uploadSettings.unlimited) savePreference { model.setUnlimitedBackup(false) } else unlimitedConfirmation = true }, enabled = !backupSaving) { Text(if (state.uploadSettings.unlimited) label("Use limited data again", "আবার সীমিত ডেটা ব্যবহার") else label("No limit · Unlimited internet", "সীমা নেই · আনলিমিটেড ইন্টারনেট")) }
                                            }
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Backup & Sync")) {
                                    items(state.backups, key = { it.id }) { backup -> ListItem(headlineContent = { Text(backup.name) }, trailingContent = { TextButton({ model.stopBackup(backup.id) }) { Text(label("Stop", "বন্ধ")) } }) }
                                }
                                if (settingsCategory in setOf("Theme")) {
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(crashSummary, style = MaterialTheme.typography.labelSmall)
                                                Text(label("Interface style", "ইন্টারফেসের ধরন"), style = MaterialTheme.typography.titleMedium)
                                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                                    InterfaceStyle.entries.forEachIndexed { index, style ->
                                                        SegmentedButton(selected = interfaceStyle == style, onClick = { savePreference { changeInterfaceStyle(style) } }, shape = SegmentedButtonDefaults.itemShape(index, InterfaceStyle.entries.size)) {
                                                            Text(if (style == InterfaceStyle.GLASS) label("Glass", "গ্লাস") else "Material You", style = MaterialTheme.typography.labelMedium)
                                                        }
                                                    }
                                                }
                                                Text(if (interfaceStyle == InterfaceStyle.GLASS) label("Transparent glass surfaces · default", "স্বচ্ছ গ্লাস surface · ডিফল্ট") else label("Solid Material You surfaces", "Material You solid surface"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary).forEach { accent ->
                                                        Box(Modifier.size(24.dp).background(accent, RoundedCornerShape(50)))
                                                    }
                                                }
                                                Text(label("Appearance", "থিম"), style = MaterialTheme.typography.titleMedium)
                                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                                    AppearanceMode.entries.forEachIndexed { index, mode ->
                                                        SegmentedButton(selected = appearanceMode == mode, onClick = { savePreference { changeAppearanceMode(mode) } }, shape = SegmentedButtonDefaults.itemShape(index, AppearanceMode.entries.size)) {
                                                            Text(
                                                                when (mode) {
                                                                    AppearanceMode.SYSTEM -> label("System", "সিস্টেম")
                                                                    AppearanceMode.LIGHT -> label("Light", "লাইট")
                                                                    AppearanceMode.DARK -> label("Dark", "ডার্ক")
                                                                },
                                                                style = MaterialTheme.typography.labelMedium
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Player")) {
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            ListItem(headlineContent = { Text(label("Resume last location", "শেষ অবস্থানে আবার খুলুন")) }, supportingContent = { Text(label("Reopen your last normal tab and folder. Turn off to start at Photos next time; your current screen stays in place. Private Vault never reopens.", "শেষ সাধারণ ট্যাব ও ফোল্ডারে আবার খুলবে। বন্ধ করলে পরের বার Photos-এ শুরু হবে; বর্তমান স্ক্রিন সরবে না। Private Vault আবার খুলবে না।")) }, trailingContent = { Switch(resumeLastLocation, { enabled -> savePreference { model.preferences.setResumeLastLocation(enabled) } }) })
                                        }
                                    }
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            ListItem(headlineContent = { Text(label("Return to the viewed item", "দেখা ফাইলের অবস্থানে ফিরুন")) }, supportingContent = { Text(label("Return to the last viewed photo, video, audio or document in its list and briefly highlight it. After reopening, this also needs Resume last location. Private Vault is excluded.", "তালিকায় শেষ দেখা ফটো, ভিডিও, অডিও বা ডকুমেন্টে ফিরে হালকা চিহ্ন দেখাবে। অ্যাপ আবার খুললে শেষ অবস্থানে খোলার সেটিংটিও চালু থাকতে হবে। Private Vault বাদ থাকবে।")) }, trailingContent = { Switch(returnToViewedItem, { enabled -> savePreference { model.preferences.setReturnToViewedItem(enabled) } }) })
                                        }
                                    }
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            ListItem(headlineContent = { Text(label("Tap to reveal viewer controls", "ট্যাপ করে ভিউয়ারের কন্ট্রোল দেখুন")) }, supportingContent = { Text(label("Start with controls hidden in photos, videos, audio and documents. Tap to show or hide; controls stay visible while held. Shorts always use this behavior.", "ফটো, ভিডিও, অডিও ও ডকুমেন্টে কন্ট্রোল লুকানো দিয়ে শুরু হবে। ট্যাপে দেখান বা লুকান; ধরে রাখলে লুকাবে না। Shorts-এ সবসময় চালু।")) }, trailingContent = { Switch(tapToRevealControls, { enabled -> savePreference { model.preferences.setTapToRevealControls(enabled) } }) })
                                        }
                                    }
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(label("Animation duration", "অ্যানিমেশনের সময়"), style = MaterialTheme.typography.titleMedium)
                                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    (0..4).forEach { mode -> FilterChip(animationMode == mode, { savePreference { model.preferences.setAnimationMode(mode) } }, label = { Text(if (mode == 0) label("Smart", "স্মার্ট") else "$mode×") }) }
                                                }
                                                Text(label("Smart is balanced. Larger values make transitions longer; video speed stays unchanged. Android reduced motion is respected.", "স্মার্ট ডিফল্ট। বড় মানে অ্যানিমেশন ধীর ও দীর্ঘ হবে; ভিডিওর গতি বদলাবে না। Android-এর reduced motion মানা হবে।"), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Theme")) {
                                    item { AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { ListItem(headlineContent = { Text(label("Material You colors", "Material You রং")) }, supportingContent = { Text(label("Match your phone's wallpaper", "ফোনের ওয়ালপেপারের সঙ্গে মিলবে")) }, trailingContent = { Switch(dynamicColor, { savePreference { changeDynamicColor(it) } }) }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)) } }
                                }
                                if (settingsCategory in setOf("Security")) {
                                    item {
                                        AppSurface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                            ListItem(headlineContent = { Text(label("24-hour Trash protection", "ট্র্যাশে ২৪ ঘণ্টার সুরক্ষা")) }, supportingContent = { Text(label("Wait before permanent deletion. Restore anytime. Cloud Trash stays until you delete it.", "স্থায়ীভাবে মোছার আগে অপেক্ষা। যেকোনো সময় ফিরিয়ে আনুন। ক্লাউড ট্র্যাশ নিজে থেকে মুছবে না।")) }, trailingContent = { Switch(cooldown, { enabled -> if (enabled) savePreference { model.preferences.setTrashCooldownEnabled(true) } else authenticate { savePreference { model.preferences.setTrashCooldownEnabled(false) } } }) })
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Storage & Cache")) {
                                    item {
                                        OutlinedButton({ prompt = "trash-all-cloud" }, Modifier.fillMaxWidth(), enabled = state.files.any { it.trashedAt == 0L } && state.transfers.none { it.id.startsWith("trash-all:") && it.phase !in setOf("COMPLETE", "CANCELLED") }, shape = RoundedCornerShape(22.dp)) {
                                            Icon(Icons.Outlined.DeleteSweep, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(label("Move all cloud backups to Trash", "সব ক্লাউড ব্যাকআপ ট্র্যাশে রাখুন"))
                                        }
                                    }
                                }
                                if (settingsCategory in setOf("Backup & Sync")) {
                                    item {
                                        OutlinedButton({ deviceCopyActions.moveBacked(null, "gallery") }, Modifier.fillMaxWidth(), enabled = state.deviceCopyProgress == null && state.files.any { it.trashedAt == 0L } && local.any { !it.trashed }, shape = RoundedCornerShape(22.dp)) {
                                            Icon(Icons.Outlined.AutoDelete, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(label("Move backed-up device copies to Trash", "ব্যাকআপ হওয়া ডিভাইস কপি ট্র্যাশে রাখুন"))
                                        }
                                        Text(label("Only device originals with verified completed cloud backups are moved. Cloud backups stay available. Android asks for consent; unsupported originals are kept.", "যেসব original-এর সম্পূর্ণ cloud backup যাচাই হয়েছে শুধু সেগুলিই ডিভাইস থেকে ট্র্যাশে যাবে। Cloud backup থাকবে। Android অনুমতি চাইবে; অসমর্থিত original থাকবে।"), style = MaterialTheme.typography.bodySmall)
                                        if (state.deviceCleanupRequests.isNotEmpty()) TextButton({ model.finishDeviceCleanup(state.deviceCleanupRequests.map { it.id }.toSet()) }) { Text(label("Cancel pending device cleanup (${state.deviceCleanupRequests.size})", "ডিভাইসের বাকি cleanup বাতিল করুন (${state.deviceCleanupRequests.size})")) }
                                    }
                                }
                                if (settingsCategory in setOf("Backup & Sync", "Storage & Cache", "Security", "Theme", "Player", "Data Usage", "Help", "About")) {
                                    item { Spacer(Modifier.height(24.dp)) }
                                }
                            }
                        }
                    } else if (tab == PhotoTab.LIBRARY && section !in setOf("Transfers", "Trash")) {
                        libraryStateHolder.SaveableStateProvider("library:$libraryDevice") {
                            FileLibrary(
                                files = if (libraryDevice) device else cloud,
                                cloud = !libraryDevice,
                                initialFolder = libraryPath,
                                initialCategory = libraryCategory,
                                initialRecent = libraryRecent,
                                locationChanged = { path, category, recent ->
                                    libraryPath = path
                                    libraryCategory = category
                                    libraryRecent = recent
                                },
                                chooseCloud = { cloudView ->
                                    libraryDevice = !cloudView
                                    libraryPath = ""
                                    libraryCategory = "All files"
                                    libraryRecent = false
                                    search = ""
                                    folder = null
                                    selected = emptySet()
                                },
                                selected = selected,
                                shownFilesChanged = { libraryShownIds = it },
                                selection = { selected = it },
                                open = { item, entries ->
                                    viewerEntries = entries
                                    opening = item
                                },
                                backup = { item ->
                                    item.local?.let {
                                        model.upload(listOf(it.uri))
                                        notifyAccess()
                                    }
                                },
                                search = search,
                                changeSearch = { search = it },
                                model = model,
                                loading = if (libraryDevice) !deviceIndexReady || scanningFiles else !state.indexLoaded && !state.indexFailed,
                                scanFailed = if (libraryDevice) deviceScanFailed else state.indexFailed,
                                backedSources = state.backedSources,
                                access = if (libraryDevice) ({ requestAllFiles() }) else null,
                                chooseFolder = { documentFolderPicker.launch(null) },
                                refresh = {
                                    permissionsChanged++
                                    model.restoreVisibleIndex()
                                    model.refreshCloudExports()
                                },
                                utilities = {
                                    if (it.startsWith("Automatic backup folder")) {
                                        backupPicker.launch(it.substringAfter(':', "").takeIf { value -> value.isNotBlank() }?.let(android.net.Uri::parse))
                                    } else if (it == "Create folder") {
                                        prompt = if (libraryDevice) "device-new-folder" else "new-folder"
                                        name = ""
                                    } else {
                                        section = it
                                        selected = emptySet()
                                    }
                                },
                                cloudFolders = state.albums.filter { it.folder }.map { it.name },
                                fileAction = { item, action ->
                                    when (action) {
                                        "Keep offline" -> model.pinOffline(setOf(item.id))
                                        "Remove offline" -> model.removeOffline(setOf(item.id))
                                        "Compress ZIP" -> {
                                            archiveEntries = listOf(item)
                                            archiveConsent = true
                                        }
                                        "Rename" -> {
                                            renameEntry = item
                                            renameValue = item.name
                                        }
                                        "Private Vault" -> {
                                            pendingVaultCopies = listOf(item.local?.uri ?: android.net.Uri.parse("content://${context.packageName}.private-media/media/${item.cloud!!.id}"))
                                            switchTab(PhotoTab.VAULT)
                                        }
                                        "Backup and trash" -> item.local?.let {
                                            model.backupAndTrash(listOf(it), "library")
                                            notifyAccess()
                                        }
                                        "Remove device copy" -> trashActions.request(listOf(item), "library", app.televault.domain.TrashChoice.Scope.DEVICE)
                                        "Trash" -> deleteEntries(listOf(item))
                                        "Save copy" -> save(item)
                                        "Move", "Copy" -> {
                                            selected = setOf(item.id)
                                            prompt = if (action == "Move") "move" else "copy"
                                            name = ""
                                        }
                                        else -> scope.launch {
                                            val shared = app.televault.data.PrivateMediaGrants.create(context.packageName, item.local?.uri, item.cloud?.id, item.name, item.mime, item.size, model.playbackEpoch)
                                            val intent = Intent(if (action == "Share") Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                                                if (action == "Share") {
                                                    type = item.mime
                                                    putExtra(Intent.EXTRA_STREAM, shared)
                                                } else {
                                                    setDataAndType(shared, item.mime)
                                                }
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                clipData = android.content.ClipData.newRawUri(item.name, shared)
                                            }
                                            runCatching { context.startActivity(Intent.createChooser(intent, item.name)) }.onFailure { model.report("No compatible app was found.") }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                scroll = browseList,
                                leadingContent = browsingIntroduction
                            )
                        }
                    } else {
                        when (section) {
                            "Transfers" -> TransferQueue(model)
                            "Albums", "Folders" -> {
                                if (tab == PhotoTab.GALLERY && local.isEmpty() && (!deviceIndexReady || scanningFiles || deviceScanFailed)) {
                                    if (deviceScanFailed || showLibraryFeedback) Text(if (deviceScanFailed) label("Device scan could not finish. Refresh to try again.", "ফোনের স্ক্যান শেষ হয়নি। রিফ্রেশ করে আবার চেষ্টা করুন।") else label("Reading device media…", "ফোনের মিডিয়া পড়ছে…"), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                                }
                                AlbumsGrid(
                                    if (tab == PhotoTab.GALLERY && local.isEmpty() && (!deviceIndexReady || scanningFiles)) {
                                        emptyList()
                                    } else if (tab == PhotoTab.GALLERY) {
                                        deviceAlbums
                                    } else {
                                        emptyList()
                                    },
                                    if (tab == PhotoTab.GALLERY) {
                                        emptyList()
                                    } else if (section == "Folders") {
                                        cloudAlbums.filter { it.folder != null }
                                    } else {
                                        cloudAlbums
                                    },
                                    model,
                                    ::openAlbum,
                                    {
                                        prompt = if (section == "Folders") "new-folder" else "album"
                                        name = ""
                                    },
                                    Modifier.weight(1f),
                                    scopeKey = "${tab.name}:$section:$folder:$album",
                                    grid = timelineGrid,
                                    leadingContent = browsingIntroduction,
                                    deviceOnly = tab == PhotoTab.GALLERY,
                                    peopleContent = {
                                        PeopleAlbums(people, model) { person ->
                                            searchPerson = person.id
                                            universalSearch = true
                                        }
                                        if (tab == PhotoTab.GALLERY) {
                                            TextButton({
                                                section = "Trash"
                                                trashScope = TrashScope.DEVICE
                                                trashFiles = false
                                                folder = null
                                                album = null
                                                selected = emptySet()
                                            }, Modifier.fillMaxWidth()) {
                                                Icon(Icons.Outlined.DeleteOutline, null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(label("Recently deleted", "সম্প্রতি মুছে ফেলা"))
                                            }
                                        }
                                    }
                                )
                            }
                            else -> {
                                if (visible.isEmpty() && isBrowse) browsingIntroduction()
                                if (tab == PhotoTab.LIBRARY && libraryDevice && libraryFolders && folder == null && section in setOf("Documents", "Audio", "All files") && visible.isNotEmpty()) {
                                    DeviceLibraryFolders(visible, scroll = browseList, leadingContent = browsingIntroduction) { path ->
                                        folder = path
                                        selected = emptySet()
                                    }
                                } else if (visible.isEmpty() && projection.failed) {
                                    Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text(label("Could not display this view", "এই তালিকা দেখানো যায়নি"), style = MaterialTheme.typography.titleMedium)
                                        Text(label("Your saved files have not been cleared. Retry this view.", "আপনার সেভ করা ফাইল ক্লিয়ার হয়নি। আবার চেষ্টা করুন।"))
                                        TextButton(projection.retry) { Text(label("Retry", "আবার চেষ্টা")) }
                                    }
                                } else if (visible.isEmpty() && (!projection.ready || (if (deviceContext) !deviceIndexReady || scanningFiles else !state.indexLoaded))) {
                                    val failed = if (deviceContext) deviceScanFailed else state.indexFailed
                                    Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        if (failed || showLibraryFeedback) {
                                            if (!failed) SecureLoader(Modifier.padding(bottom = 16.dp).size(28.dp))
                                            Text(
                                                if (failed) {
                                                    label("Could not restore your index", "আপনার তালিকা ফেরানো যায়নি")
                                                } else if (deviceContext) {
                                                    label("Reading your device library…", "ফোনের লাইব্রেরি পড়ছে…")
                                                } else {
                                                    label("Restoring your encrypted library…", "আপনার এনক্রিপ্টেড লাইব্রেরি ফিরছে…")
                                                },
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(label("Your files are kept. The local index opens before cloud sync finishes.", "আপনার ফাইল রয়েছে। ক্লাউড সিঙ্ক শেষ হওয়ার আগেই লোকাল তালিকা খুলবে।"), Modifier.padding(top = 8.dp))
                                            TextButton({ if (deviceContext) permissionsChanged++ else model.restoreVisibleIndex() }) { Text(label("Retry", "আবার চেষ্টা")) }
                                        }
                                    }
                                } else if (visible.isEmpty()) {
                                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        AppSurface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(if (tab == PhotoTab.LIBRARY) Icons.Outlined.FolderOpen else Icons.Outlined.PhotoLibrary, null, Modifier.padding(26.dp).size(42.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                                        Spacer(Modifier.height(22.dp))
                                        Text(if (mediaFilter.active && section != "Trash") label("No matching media", "মিলে যাওয়া মিডিয়া নেই") else label("A little space for your memories", "আপনার স্মৃতির জন্য জায়গা"), style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            if (mediaFilter.active && section != "Trash") {
                                                label("Change the category or turn off Clean view. Files and background backup are unchanged.", "ক্যাটাগরি বদলান বা ক্লিন ভিউ বন্ধ করুন। ফাইল ও ব্যাকগ্রাউন্ড ব্যাকআপ অপরিবর্তিত আছে।")
                                            } else if (tab == PhotoTab.LIBRARY && libraryDevice && section in setOf("Documents", "All files")) {
                                                label("Choose folders to browse your documents securely.", "ডকুমেন্ট দেখতে নিরাপদভাবে ফোল্ডার বাছুন।")
                                            } else if (deviceContext) {
                                                label("Allow media access to see your device folders.", "ফোনের ফোল্ডার দেখতে মিডিয়ার অনুমতি দিন।")
                                            } else {
                                                label("Back up a file to see it here.", "এখানে দেখতে একটি ফাইল ব্যাকআপ করুন।")
                                            },
                                            Modifier.padding(top = 8.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (mediaFilter.active && section != "Trash" && base.any { it.cloud?.trashedAt == 0L || it.local?.trashed == false }) {
                                            Text(label("${base.count { it.cloud?.trashedAt == 0L || it.local?.trashed == false }} files are available before filters.", "ফিল্টারের আগে ${base.count { it.cloud?.trashedAt == 0L || it.local?.trashed == false }}টি ফাইল রয়েছে।"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                                            TextButton(mediaFilters.reset) { Text(label("Reset category filters", "ক্যাটাগরি ফিল্টার রিসেট")) }
                                        } else if (deviceContext) {
                                            if (tab == PhotoTab.LIBRARY && section in setOf("Documents", "All files")) {
                                                TextButton({ allFilesIntroduction = true }) { Text(label("Allow shared-storage files", "ফোনের shared ফাইলের অনুমতি দিন")) }
                                                TextButton({ documentFolderPicker.launch(null) }) { Text(label("Choose individual folder", "আলাদা ফোল্ডার বাছুন")) }
                                                Text(label("You can grant all shared-files access in Android Settings or select individual folders. Android/data, Android/obb and private app storage remain protected.", "Android Settings থেকে সব shared ফাইলের অনুমতি দিন, অথবা আলাদা ফোল্ডার বাছুন। Android/data, Android/obb ও private storage সুরক্ষিত থাকে।"), style = MaterialTheme.typography.bodySmall)
                                            } else {
                                                TextButton({ permission.launch(permissions()) }) { Text(label("Allow media access", "মিডিয়ার অনুমতি দিন")) }
                                            }
                                        }
                                    }
                                } else {
                                    val click: (MediaEntry) -> Unit = { item -> if (selected.isEmpty()) open(item) else selectVisible(if (item.id in selected) selected - item.id else selected + item.id) }
                                    val hold: (MediaEntry) -> Unit = { item -> selectVisible(selected + item.id) }
                                    if (section in setOf("Documents", "Audio", "All files") || (section == "Trash" && visible.none { it.mime.startsWith("image/") || it.mime.startsWith("video/") })) {
                                        DocumentTimeline(visible, selected, model, density, { density = it }, click, hold, scroll = browseList, leadingContent = browsingIntroduction, showDateHeaders = showTimelineDates)
                                    } else {
                                        MediaGrid(
                                            visible, selected, model, deviceContext, click, hold, density, { density = it }, grid = timelineGrid, mosaic = mosaicLayout, selectionChanged = { selectVisible(it) },
                                            leadingContent = if (isBrowse) browsingIntroduction else null, showDateHeaders = showTimelineDates, onFastScroll = {
                                                initialScrollInterrupted = true
                                                clearReturnFocus()
                                            }, fastScrollTop = if (compactChromeTransition.targetState || compactChromeTransition.currentState) compactChromeHeight else 0.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(compactChromeTransition, Modifier.align(Alignment.TopCenter).then(Modifier.onSizeChanged { compactChromeHeight = with(context.resources.displayMetrics.density) { (it.height / this).dp } }).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp), enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { -it / 8 }, exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))) {
                    if (introductionCleared) {
                        if (isPhotoTimeline) {
                            PhotoSectionPills(section, choosePhotoSection)
                        } else {
                            AppSurface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                                Column {
                                    if (!isPhotoTimeline && section != "Trash") BrowseOptions(sectionLabel(section), active = mediaFilters.options.category != app.televault.domain.MediaCategories.Category.ALL) { MediaFilterBar(mediaFilters) }
                                    if (section !in setOf("Albums", "Folders", "Trash")) {
                                        PhotoSectionPills(section, { key ->
                                            section = key
                                            selected = emptySet()
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
                CloudRestoreNotice(model, eligible = shortcutEligible && exploreMode == null && telegramWarningSeen, modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 76.dp))
            }
        }
        pendingBackupFolder?.let { target ->
            AlertDialog(onDismissRequest = { pendingBackupFolder = null }, title = { Text(label("Enable folder backup?", "ফোল্ডার ব্যাকআপ চালু করবেন?")) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(label("Existing and new readable files in this selected folder will be encrypted and backed up. Browsing other folders never enables backup.", "নির্বাচিত ফোল্ডারের পুরোনো ও নতুন পাঠযোগ্য ফাইল encrypt হয়ে ব্যাকআপ হবে। অন্য ফোল্ডার দেখলে ব্যাকআপ চালু হয় না।"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label("Wi-Fi only", "শুধু Wi-Fi"), Modifier.weight(1f))
                        Switch(backupWifiOnly, { backupWifiOnly = it })
                    }
                    if (state.uploadSettings.dataSaver) Text(label("Data Saver pauses this folder's automatic backup until you turn it off.", "Data Saver বন্ধ না করা পর্যন্ত এই ফোল্ডারের অটো ব্যাকআপ অপেক্ষা করবে।"), style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = {
                TextButton({
                    model.backup(target, backupWifiOnly)
                    notifyAccess()
                    pendingBackupFolder = null
                }) { Text(label("Enable", "চালু করুন")) }
            }, dismissButton = { TextButton({ pendingBackupFolder = null }) { Text(label("Cancel", "বাতিল")) } })
        }
        if (showOfflineDownloads) OfflineDownloadsSheet(model) { showOfflineDownloads = false }
        if (telegramSessions) TelegramSessionsDialog(model) { telegramSessions = false }
        if (archiveConsent) {
            AlertDialog(onDismissRequest = {
                archiveConsent = false
                archiveEntries = emptyList()
            }, title = { Text(label("Export ZIP copy?", "ZIP কপি এক্সপোর্ট করবেন?")) }, text = { Text(label("This ZIP contains decrypted originals. Other apps can read it in your chosen folder. Source files remain unchanged.", "এই ZIP-এ ডিক্রিপ্টেড মূল ফাইল থাকবে। নির্বাচিত ফোল্ডারে অন্য অ্যাপ পড়তে পারে। মূল ফাইল অক্ষত থাকবে।")) }, confirmButton = {
                TextButton({
                    archiveConsent = false
                    archiveDestination.launch("TG-Photos-Files-${System.currentTimeMillis()}.zip")
                }) { Text(label("Choose destination", "গন্তব্য বাছুন")) }
            }, dismissButton = {
                TextButton({
                    archiveConsent = false
                    archiveEntries = emptyList()
                }) { Text(label("Cancel", "বাতিল")) }
            })
        }
        renameEntry?.let { item ->
            AlertDialog(onDismissRequest = { renameEntry = null }, title = { Text(label("Rename file", "ফাইলের নাম বদলান")) }, text = { OutlinedTextField(renameValue, { renameValue = it.take(240) }, singleLine = true, label = { Text(label("Filename", "ফাইলের নাম")) }) }, confirmButton = {
                TextButton({
                    val next = renameValue
                    renameEntry = null
                    if (item.cloud != null) {
                        model.renameFile(item.cloud.id, next)
                    } else {
                        item.local?.let { local ->
                            scope.launch {
                                try {
                                    LocalFileActions.rename(context, local, next)
                                    model.localMedia.refresh()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    model.report("Rename was not permitted. Use the Android folder picker or choose a different filename. Original kept.")
                                }
                            }
                        }
                    }
                }, enabled = renameValue.isNotBlank() && renameValue !in setOf(".", "..") && renameValue.none { it == '/' || it == '\\' || it.isISOControl() }) { Text(label("Rename", "নাম বদলান")) }
            }, dismissButton = { TextButton({ renameEntry = null }) { Text(label("Cancel", "বাতিল")) } })
        }
        if (allFilesIntroduction) {
            AlertDialog(onDismissRequest = { allFilesIntroduction = false }, icon = { Icon(Icons.Outlined.FolderOpen, null) }, title = { Text(label("Browse your device files", "ফোনের ফাইল দেখুন")) }, text = {
                Text(label("Allow shared-storage access in Android Settings to see non-empty folders and files of every type. This only reads your files; it never enables backup or uploads them. Private app storage stays protected. You can instead select individual folders.", "খালি নয় এমন ফোল্ডার ও সব ধরনের ফাইল দেখতে Android Settings-এ shared storage-এর অনুমতি দিন। এতে শুধু ফাইল দেখা যায়; ব্যাকআপ চালু বা আপলোড হয় না। অ্যাপের private storage সুরক্ষিত থাকে। চাইলে আলাদা ফোল্ডারও বাছতে পারেন।"))
            }, confirmButton = {
                TextButton({
                    allFilesIntroduction = false
                    requestAllFiles()
                }) { Text(label("Allow access", "অনুমতি দিন")) }
            }, dismissButton = {
                TextButton({
                    allFilesIntroduction = false
                    documentFolderPicker.launch(null)
                }) { Text(label("Choose folder", "ফোল্ডার বাছুন")) }
            })
        }
        if (galleryIntroduction) {
            AlertDialog(onDismissRequest = {
                galleryIntroduction = false
                savePreference {
                    model.preferences.markGalleryIntroductionSeen()
                    model.preferences.markMediaSetupSeen()
                }
                firstLoginPermissions = false
            }, icon = { Icon(Icons.Outlined.PrivacyTip, null) }, title = { Text(label("Your device gallery, your choice", "আপনার গ্যালারি, আপনার সিদ্ধান্ত")) }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label("Allow photos, videos and audio to browse your phone. On Android you can allow all photos or selected photos, and decline any permission.", "ফোনের ছবি, ভিডিও ও অডিও দেখতে অনুমতি দিন। Android-এ সব ছবি বা বাছাই করা ছবি দেখার অনুমতি দিতে পারেন; যেকোনো অনুমতি না-ও দিতে পারেন।"))
                    Text(label("Documents need a separate Android folder-selection grant in Library.", "ডকুমেন্টের জন্য Library থেকে আলাদাভাবে Android-এর ফোল্ডার বাছতে হবে।"), style = MaterialTheme.typography.bodySmall)
                    if (firstLoginPermissions) {
                        Text(label("Turn on automatic Camera backup?", "Camera অটো ব্যাকআপ চালু করবেন?"), style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(setupCamera, { setupCamera = it })
                            Text(label("Back up Camera automatically after permission", "অনুমতি পেলে Camera অটো ব্যাকআপ করুন"), Modifier.weight(1f))
                        }
                        androidx.compose.animation.AnimatedVisibility(setupCamera) { BackupPlanChoices(setupUnlimited, { setupUnlimited = it }) }
                        Text(label("Manual Upload and downloads have no daily app data cap. Change automatic backup later in Settings → Backup & Sync / Data Usage.", "Manual Upload ও ডাউনলোডে অ্যাপের দৈনিক ডেটা সীমা নেই। পরে Settings → Backup & Sync / Data Usage থেকে অটো ব্যাকআপ বদলাতে পারবেন।"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.backups.isNotEmpty()) Text(label("You already have automatic backup enabled for selected folders. Manage it in Settings.", "আগে থেকে কিছু ফোল্ডারের অটো-ব্যাকআপ চালু আছে। Settings থেকে বন্ধ বা পরিবর্তন করতে পারেন।"), style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = {
                TextButton({
                    val firstSetup = firstLoginPermissions
                    savePreference {
                        if (firstSetup && setupCamera) {
                            model.setUnlimitedBackup(setupUnlimited)
                            enableCameraAfterPermission = setupCamera
                        }
                        model.preferences.markGalleryIntroductionSeen()
                        model.preferences.markMediaSetupSeen()
                        galleryIntroduction = false
                        permission.launch(permissions() + if (firstSetup && Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
                        firstLoginPermissions = false
                    }
                }) { Text(if (firstLoginPermissions) if (setupCamera) label("Enable Camera backup & continue", "Camera ব্যাকআপ চালু করে এগোন") else label("Browse without auto backup", "অটো ব্যাকআপ ছাড়া দেখুন") else label("Choose permissions", "অনুমতি বাছুন")) }
            }, dismissButton = {
                TextButton({
                    galleryIntroduction = false
                    savePreference {
                        model.preferences.markGalleryIntroductionSeen()
                        model.preferences.markMediaSetupSeen()
                    }
                }) { Text(label("Not now", "এখন নয়")) }
            })
        }
        if (videoDataConfirmation) {
            AlertDialog(onDismissRequest = { videoDataConfirmation = false }, title = { Text(label("Unlimited mobile video backup?", "সীমাহীন মোবাইল ভিডিও ব্যাকআপ?")) }, text = { Box(Modifier.verticalScroll(rememberScrollState())) { Text(label("This permits videos of any length without the app's daily mobile-data allowance. Confirm that your internet plan supports these uploads. Photos keep their own daily allowance unless No limit is enabled.", "এতে দৈর্ঘ্য ও অ্যাপের দৈনিক ডেটা সীমা ছাড়া মোবাইল ডেটায় ভিডিও আপলোড হবে। আপনার ইন্টারনেট প্ল্যানে এই আপলোড করা যাবে বলে নিশ্চিত করুন। No limit চালু না করলে ছবির দৈনিক সীমা থাকবে।")) } }, confirmButton = {
                TextButton({
                    model.settings(state.uploadSettings.photoLimitMb, true, state.uploadSettings.cameraBackup)
                    notifyAccess()
                    videoDataConfirmation = false
                }) { Text(label("My plan supports this · Enable", "আমার প্ল্যানে চলবে · চালু করুন")) }
            }, dismissButton = { TextButton({ videoDataConfirmation = false }) { Text(label("Keep limited backup", "সীমিত ব্যাকআপ রাখুন")) } })
        }
        if (unlimitedConfirmation) {
            AlertDialog(onDismissRequest = { unlimitedConfirmation = false }, icon = { Icon(Icons.Outlined.CloudUpload, null) }, title = { Text(label("Use unlimited internet?", "আনলিমিটেড ইন্টারনেট ব্যবহার করবেন?")) }, text = { Box(Modifier.verticalScroll(rememberScrollState())) { Text(label("Full backup removes the app's daily mobile-data and Camera video-length limits for your enabled backup folders. Large videos can use a lot of data. Confirm that your plan has unlimited data. Permissions, selected folders, Data Saver and battery protection still apply.", "Full backup-এ চালু ব্যাকআপ ফোল্ডারের দৈনিক মোবাইল ডেটা ও ক্যামেরা ভিডিওর দৈর্ঘ্যের সীমা থাকবে না। বড় ভিডিওতে অনেক ডেটা লাগতে পারে। আপনার আনলিমিটেড ডেটা আছে বলে নিশ্চিত করুন। পারমিশন, বাছাই করা ফোল্ডার, Data Saver ও ব্যাটারি সুরক্ষা কার্যকর থাকবে।")) } }, confirmButton = {
                TextButton({
                    savePreference {
                        model.setUnlimitedBackup(true)
                        unlimitedConfirmation = false
                    }
                }) { Text(label("I have unlimited internet · Enable", "আমার আনলিমিটেড নেট আছে · চালু করুন")) }
            }, dismissButton = { TextButton({ unlimitedConfirmation = false }) { Text(label("Keep limited backup", "সীমিত ব্যাকআপ রাখুন")) } })
        }
        if (changePassword) PasswordChangeDialog(model) { changePassword = false }
        LaunchedEffect(prompt) { if (prompt == "camera") setupUnlimited = state.uploadSettings.unlimited }
        if (prompt.isNotEmpty()) {
            when (prompt) {
                "logout" -> AlertDialog(onDismissRequest = { prompt = "" }, title = { Text(label("Log out?", "লগআউট করবেন?")) }, text = { Text(label("Your encrypted cloud files stay. Keep the recovery phrase before ending this app's Telegram session.", "এনক্রিপ্টেড ক্লাউড ফাইল থাকবে। Telegram সেশন বন্ধের আগে রিকভারি পাসফ্রেজ রাখুন।")) }, confirmButton = {
                    TextButton({
                        prompt = ""
                        logout()
                    }) { Text(label("Log out", "লগআউট")) }
                }, dismissButton = { TextButton({ prompt = "" }) { Text(label("Cancel", "বাতিল")) } })
                "export" -> AlertDialog(onDismissRequest = { prompt = "" }, title = { Text(label("Save original files?", "মূল ফাইল সেভ করবেন?")) }, text = { Text(label("Files saved outside TG Photos are plaintext. Choose a destination folder.", "TG Photos-এর বাইরে সেভ করা ফাইল plaintext হবে। ফোল্ডার বাছুন।")) }, confirmButton = {
                    TextButton({
                        prompt = ""
                        destination.launch(null)
                    }) { Text(label("Choose folder", "ফোল্ডার বাছুন")) }
                }, dismissButton = { TextButton({ prompt = "" }) { Text(label("Cancel", "বাতিল")) } })
                "trash-all-cloud" -> PasswordTrashConfirmation(model, state.files.count { it.trashedAt == 0L }, cancel = { prompt = "" }, confirmed = {
                    prompt = ""
                }, allCloud = true)
                "camera", "folder-backup" -> AlertDialog(onDismissRequest = { prompt = "" }, title = { Text(label("Enable automatic backup?", "অটো ব্যাকআপ চালু করবেন?")) }, text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label("Choose your backup plan before enabling it. Only folders you enable and grant access to are backed up. Turn it off later in Settings → Backup & Sync.", "চালু করার আগে ব্যাকআপের পছন্দ বাছুন। আপনি চালু করা ও অনুমতি দেওয়া ফোল্ডারই ব্যাকআপ হবে। পরে Settings → Backup & Sync থেকে বন্ধ করবেন।"))
                        if (prompt == "camera") BackupPlanChoices(setupUnlimited, { setupUnlimited = it })
                        Text(label("Manual Upload and downloads have no daily app data limit. Android access, network availability and battery protections still apply.", "Manual Upload ও ডাউনলোডে অ্যাপের দৈনিক ডেটা সীমা নেই। Android-এর অনুমতি, নেটওয়ার্ক ও ব্যাটারির সুরক্ষা কার্যকর থাকবে।"), style = MaterialTheme.typography.bodySmall)
                    }
                }, confirmButton = {
                    TextButton({
                        val camera = prompt == "camera"
                        if (camera) {
                            savePreference {
                                model.setUnlimitedBackup(setupUnlimited)
                                prompt = ""
                                enableCameraAfterPermission = true
                                permission.launch(permissions())
                                notifyAccess()
                            }
                        } else {
                            prompt = ""
                            backupPicker.launch(null)
                        }
                    }) { Text(if (prompt == "camera") label("Enable Camera backup", "Camera ব্যাকআপ চালু করুন") else label("Choose folder", "ফোল্ডার বাছুন")) }
                }, dismissButton = { TextButton({ prompt = "" }) { Text(label("Cancel", "বাতিল")) } })
                else -> AlertDialog(onDismissRequest = { prompt = "" }, title = {
                    Text(
                        when (prompt) {
                            "album" -> label("New cloud album", "নতুন ক্লাউড অ্যালবাম")
                            "copy" -> label("Copy / paste into folder", "ফোল্ডারে কপি / পেস্ট")
                            "move" -> label("Move / paste into folder", "ফোল্ডারে সরান / পেস্ট")
                            else -> label("Create folder", "ফোল্ডার তৈরি")
                        }
                    )
                }, text = {
                    Column {
                        OutlinedTextField(name, { name = it.take(240) }, label = { Text(label("Folder / album name", "ফোল্ডার / অ্যালবামের নাম")) })
                        Text(label("Type a new folder name here to create it while pasting.", "পেস্ট করার সময় এখানেই নতুন ফোল্ডারের নাম লিখে তৈরি করুন।"))
                        (state.albums.filter { it.folder }.map { it.name } + state.files.map { it.album }).distinct().take(10).forEach { f -> TextButton({ name = f }) { Text(f) } }
                    }
                }, confirmButton = {
                    TextButton({
                        val action = prompt
                        prompt = ""
                        val ids = selectedItems.mapNotNull { it.cloud?.id }.toSet()
                        when (action) {
                            "album" -> model.album(name, selectedItems.mapNotNull { it.cloud?.takeIf { c -> c.mime.startsWith("image/") && c.trashedAt == 0L }?.id }.toSet())
                            "new-folder" -> model.album(name, emptySet(), true)
                            "device-new-folder" -> {
                                pendingNewFolder = name.trim()
                                localDestination.launch(null)
                            }
                            else -> {
                                if (ids.isNotEmpty())model.folder(ids, name, action == "copy")
                                if (selectedItems.any { it.local != null }) {
                                    pendingLocalMove = selectedItems.filter { it.local != null }
                                    localCopy = action == "copy"
                                    localDestination.launch(null)
                                }
                            }
                        }
                        selected = emptySet()
                    }, enabled = name.isNotBlank()) { Text(label("Save / paste", "সেভ / পেস্ট")) }
                }, dismissButton = { TextButton({ prompt = "" }) { Text(label("Cancel", "বাতিল")) } })
            }
        }
        LaunchedEffect(state.uploadSettings.dailyLimitBytes) { budgetDismissed = false }
        if (!budgetDismissed && !state.uploadSettings.unlimited && (state.uploadSettings.usedBytes >= state.uploadSettings.dailyLimitBytes || state.transfers.any { it.phase == "DAILY_PHOTO_LIMIT" })) {
            AlertDialog(onDismissRequest = { budgetDismissed = true }, title = { Text(label("Automatic backup data limit reached", "অটো ব্যাকআপের ডেটা সীমা শেষ")) }, text = { Text(label("Automatic backup waits for Wi-Fi or tomorrow. Add 200 MB for today; manual Upload is always available without this limit.", "অটো ব্যাকআপ Wi-Fi বা আগামীকাল পর্যন্ত অপেক্ষা করবে। আজ ২০০ MB বাড়াতে পারেন; Manual Upload-এ এই সীমা প্রযোজ্য নয়।")) }, confirmButton = {
                TextButton({
                    savePreference { model.overrideLimit() }
                    budgetDismissed = true
                }) { Text(label("Allow extra today · +200 MB", "আজ অতিরিক্ত · +২০০ MB")) }
            }, dismissButton = { TextButton({ budgetDismissed = true }) { Text(label("Keep limit", "সীমা রাখুন")) } })
        }
        permanentDelete?.let { captured ->
            fun remaining(item: MediaEntry): Long = TrashCooldown.remaining(item.cloud?.trashedAt ?: allTrashDates[item.id] ?: 0L, System.currentTimeMillis(), cooldown)
            val waiting = captured.maxOfOrNull { remaining(it) } ?: 0L
            if (waiting > 0L) {
                AlertDialog(onDismissRequest = { permanentDelete = null }, title = { Text(label("24-hour protection", "২৪ ঘণ্টার সুরক্ষা")) }, text = { Text(label("Some selected items are still protected. Try again in ", "কিছু নির্বাচিত আইটেম এখনও সুরক্ষিত। পরে চেষ্টা করুন: ") + "${(waiting + 59999) / 60000} " + label("minutes. You can restore them now or change protection in Settings.", "মিনিট। এখন ফিরিয়ে আনতে পারেন বা সেটিংসে সুরক্ষা বদলাতে পারেন।")) }, confirmButton = { TextButton({ permanentDelete = null }) { Text(label("Keep in Trash", "ট্র্যাশে রাখুন")) } })
            } else {
                PasswordTrashConfirmation(model, captured.size, cancel = { permanentDelete = null }, confirmed = {
                    authenticate {
                        permanentDelete = null
                        val currentCooldown = cooldown
                        if (captured.any { TrashCooldown.remaining(it.cloud?.trashedAt ?: allTrashDates[it.id] ?: 0L, System.currentTimeMillis(), currentCooldown) > 0 }) {
                            model.report("Trash protection is active. Wait 24 hours after deletion.")
                        } else {
                            val remote = captured.mapNotNull { it.cloud?.id }.filter { id -> state.files.any { it.id == id && it.trashedAt > 0 } }.toSet()
                            val stored = captured.mapNotNull { it.local?.uri?.takeIf { uri -> uri.scheme == "tgphotos-device-trash" }?.schemeSpecificPart }.toSet()
                            if (stored.isNotEmpty()) scope.launch { runCatching { model.deviceFileTrash.purge(stored, currentCooldown) }.onFailure { model.report(it.message ?: "Could not empty device-file Trash.") } }
                            val originals = captured.mapNotNull { it.local }.filter { it.trashed && it.uri.scheme != "tgphotos-device-trash" }.map { it.uri }
                            if (originals.isNotEmpty()) {
                                if (Build.VERSION.SDK_INT >= 30) {
                                    runCatching { removeMoved.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, originals).intentSender).build()) }.onFailure { model.report("Android did not allow permanent deletion. Device originals were kept.") }
                                } else {
                                    model.report("Use the device file manager to empty device trash on this Android version.")
                                }
                            }
                            if (remote.isNotEmpty()) model.purge(remote)
                            selected = emptySet()
                        }
                    }
                })
            }
        }
        LaunchedEffect(showProfile, opening?.mime) {
            if (showProfile || opening?.mime?.startsWith("video/") == true) {
                try {
                    model.ensureOwnerProfile()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { /* Offline keeps encrypted profile or initials. */ }
            }
        }
        val exploreFiles = remember(cloud, device, showHiddenGallery, state.backedDeviceFiles) {
            val cloudIds = cloud.filter { it.cloud?.trashedAt == 0L }.mapTo(hashSetOf()) { it.id }
            cloud.filter { it.id in cloudIds } + device.filter { item ->
                item.local?.let { !it.trashed && (showHiddenGallery || !it.galleryHidden) && state.backedDeviceFiles[it.fingerprint] !in cloudIds } == true
            }
        }
        LaunchedEffect(pendingShorts) {
            if (pendingShorts) {
                val source = exploreFiles
                val deck = withContext(Dispatchers.Default) { app.televault.domain.ExploreMedia.deck(source.filter { it.mime.startsWith("video/") && exploreShort(it) }, { it.id }, System.nanoTime()) }
                pendingShorts = false
                if (deck.isEmpty()) {
                    model.report("No short portrait videos found yet. Open All videos while metadata finishes indexing.")
                } else {
                    exploreShorts = true
                    viewerEntries = deck
                    opening = deck.first()
                }
            }
        }
        exploreMode?.let { mode ->
            CompositionLocalProvider(LocalBrowseFocus provides browseFocus) {
                ExploreCollection(mode, exploreFiles, model, { exploreMode = null }, resumeAddress, ::clearReturnFocus) { item, collection ->
                    exploreShorts = false
                    viewerEntries = collection
                    opening = item
                }
            }
        }
        if (allPeopleVisible) {
            AllPeopleDialog(people, { allPeopleVisible = false }) { person ->
                allPeopleVisible = false
                searchPerson = person.id
                universalSearch = true
            }
        }
        if (showProfile) {
            val profileFilter = mediaFilter.copy(category = app.televault.domain.MediaCategories.Category.ALL)
            val cloudFavorites = cloud.filter { profileFilter.allows(it) && it.cloud?.trashedAt == 0L && it.cloud.favorite && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) }
            val cloudFavoriteIds = cloudFavorites.map { it.id }.toSet()
            val localFavorites = device.filter { profileFilter.allows(it) && it.local?.trashed == false && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) && (showHiddenGallery || it.local.let { file -> !file.galleryHidden && app.televault.domain.GalleryVisibility.clean(file.mime, file.name, file.folder, file.width, file.height, file.size) }) && it.id in state.localFavorites && state.backedDeviceFiles[it.local?.fingerprint] !in cloudFavoriteIds }
            CompositionLocalProvider(LocalBrowseFocus provides browseFocus) {
                PrivateProfileScreen(model, cloudFavorites + localFavorites, { showProfile = false }, { item, collection ->
                    viewerEntries = collection
                    opening = item
                })
            }
        }
        if (universalSearch) {
            val searchFiles = remember(cloud, device, showHiddenGallery, state.backedDeviceFiles) {
                val cloudIds = cloud.filter { it.cloud?.trashedAt == 0L }.mapTo(hashSetOf()) { it.id }
                cloud.filter { it.id in cloudIds } + device.filter { item ->
                    item.local?.let { !it.trashed && (showHiddenGallery || !it.galleryHidden) && state.backedDeviceFiles[it.fingerprint] !in cloudIds } == true
                }
            }
            key(searchPerson) {
                UniversalSearch(searchFiles, people, model, searchPerson, viewedId = lastViewedId, viewerOpen = opening != null, close = { universalSearch = false }) { item, collection ->
                    viewerEntries = collection
                    opening = item
                }
            }
        }
        memoryStory?.let { card ->
            MemoryStory(card, model, close = { memoryStory = null }) { item ->
                memoryStory = null
                viewerEntries = card.entries
                opening = item
            }
        }
        opening?.let { entry ->
            CompositionLocalProvider(LocalExploreFeed provides exploreShorts, LocalExploreMode provides exploreMode.orEmpty()) {
                MediaViewer(
                    entry,
                    viewerEntries ?: visible,
                    model,
                    personProfile = { id ->
                        opening = null
                        searchPerson = id
                        universalSearch = true
                    },
                    close = {
                        opening = null
                        exploreShorts = false
                    },
                    choose = { opening = it },
                    viewed = { item -> if (tab != PhotoTab.VAULT && !exploreShorts) lastViewedId = item.id },
                    profile = {
                        opening = null
                        showProfile = true
                    },
                    backup = { item, cleanup ->
                        item.local?.let { localItem ->
                            if (cleanup) {
                                model.backupAndTrash(listOf(localItem), if (tab == PhotoTab.LIBRARY) "library" else "gallery")
                                opening = null
                            } else {
                                model.upload(listOf(localItem.uri))
                            }
                            notifyAccess()
                        }
                    },
                    removeDevice = { item ->
                        opening = null
                        trashActions.request(listOf(item), if (tab == PhotoTab.LIBRARY) "library" else "gallery", app.televault.domain.TrashChoice.Scope.DEVICE)
                    },
                    save = {
                        opening = null
                        save(it)
                    },
                    trash = {
                        deleteEntries(listOf(it))
                        opening = null
                    },
                    restore = {
                        deleteEntries(listOf(it), true)
                        opening = null
                    },
                    purge = {
                        opening = null
                        permanentDelete = listOf(it)
                    },
                    favorite = { if (it.cloud != null)model.favorite(it.id, !it.cloud.favorite)else model.localFavorite(it.id, it.id !in state.localFavorites) }
                )
            }
        }
    }
}

@Composable internal fun transferLabel(phase: String): String = when (phase) {
    "DAILY_PHOTO_LIMIT" -> label("Waiting for Wi-Fi or today's extra-data permission", "Wi-Fi বা আজ অতিরিক্ত ডেটার অনুমতির অপেক্ষায়")
    "CAMERA_VIDEO_LIMIT" -> label("Camera video exceeds your automatic limit · manual Upload is available", "ক্যামেরা ভিডিও অটো সীমার বেশি · Manual Upload করতে পারেন")
    "MOBILE_VIDEO_DISABLED" -> label("Video waiting for Wi-Fi", "ভিডিও Wi-Fi-এর অপেক্ষায়")
    "COMPLETE" -> label("Completed", "সম্পূর্ণ")
    "WAITING_TRASH_COOLDOWN" -> label("Waiting for 24-hour Trash protection", "ট্র্যাশের ২৪ ঘণ্টার সুরক্ষার অপেক্ষা")
    "CANCELLED" -> label("Cancelled", "বাতিল")
    "ENCRYPTING" -> label("Encrypting", "এনক্রিপ্ট হচ্ছে")
    "VERIFYING_DEVICE" -> label("Checking this phone's original against the existing backup · not uploading", "পুরোনো ব্যাকআপের সঙ্গে ফোনের মূল ফাইল যাচাই হচ্ছে · আপলোড নয়")
    "SOURCE_UNAVAILABLE" -> label("Original file is missing or unreadable · restore access, then Retry", "মূল ফাইল নেই বা পড়া যাচ্ছে না · ফাইলের অনুমতি ফিরিয়ে Retry করুন")
    "SOURCE_ACCESS_REQUIRED" -> label("Folder or media permission is required · grant access, then Retry", "ফোল্ডার বা মিডিয়ার অনুমতি প্রয়োজন · অনুমতি দিয়ে Retry করুন")
    "WAITING_STORAGE" -> label("Not enough free space for encrypted upload staging · free space, then Retry", "এনক্রিপ্টেড আপলোডের জন্য খালি জায়গা কম · জায়গা খালি করে Retry করুন")
    "DATA_SAVER_AUTO_PAUSED" -> label("Data Saver · automatic backup paused; use manual Upload", "ডেটা সেভার · অটো ব্যাকআপ অপেক্ষায়; ম্যানুয়াল Upload করুন")
    "VIDEO_NOTICE_REQUIRED" -> label("Automatic video backup · acknowledge before upload", "অটো ভিডিও ব্যাকআপ · আপলোডের আগে জেনে অনুমতি দিন")
    "MOVING_TO_TRASH" -> label("Moving cloud backups to Trash", "ক্লাউড ব্যাকআপ ট্র্যাশে যাচ্ছে")
    "WAITING_TELEGRAM" -> label("Waiting for Telegram · retries automatically", "Telegram-এর অপেক্ষায় · স্বয়ংক্রিয়ভাবে আবার চেষ্টা হবে")
    "WAITING_VAULT" -> label("Open this job's vault to continue", "চালাতে এই কাজের ভল্ট খুলুন")
    "INDEX_REPAIR_REQUIRED" -> label("Verify the local index before automatic backup", "অটো ব্যাকআপের আগে লোকাল ইনডেক্স যাচাই করুন")
    "RETRYING" -> label("Connection interrupted · retry scheduled", "সংযোগ বিচ্ছিন্ন · আবার চেষ্টা হবে")
    "UPLOADING" -> label("Uploading", "আপলোড হচ্ছে")
    "COMMITTING_INDEX" -> label("Saving cloud record", "ক্লাউড রেকর্ড সেভ হচ্ছে")
    "DOWNLOADING" -> label("Downloading", "ডাউনলোড হচ্ছে")
    "VERIFYING" -> label("Checking original", "মূল ফাইল যাচাই হচ্ছে")
    "EXPORTING" -> label("Saving original", "মূল ফাইল সেভ হচ্ছে")
    "FAILED" -> label("Could not complete · tap Retry", "সম্পূর্ণ হয়নি · আবার চেষ্টা করুন")
    else -> label("Waiting for connection or phone unlock", "সংযোগ বা ফোন আনলকের অপেক্ষায়")
}

@Composable internal fun sectionLabel(key: String): String = when (key) {
    "All" -> label("All", "সব")
    "Photos" -> label("Photos", "ছবি")
    "Videos" -> label("Videos", "ভিডিও")
    "Movies" -> label("Movies · 60 min+", "মুভি · ৬০ মিনিট+")
    "Albums" -> label("Albums", "অ্যালবাম")
    "Folders" -> label("Folders", "ফোল্ডার")
    "Favorites" -> label("Favorites", "প্রিয়")
    "Apps" -> label("Apps", "অ্যাপ")
    "Archives" -> label("Archives", "আর্কাইভ")
    "Other files" -> label("Others", "অন্য")
    "All files" -> label("All files", "সব ফাইল")
    "Documents" -> label("Documents", "ডকুমেন্ট")
    "Audio" -> label("Audio", "অডিও")
    "Trash" -> label("Trash", "ট্র্যাশ")
    "Transfers" -> label("Transfers", "স্থানান্তর")
    "Duplicates" -> label("Duplicates", "ডুপ্লিকেট")
    else -> key
}

private data class SavedTab(val section: String, val search: String, val folder: String?, val album: String?, val device: Boolean, val searchOpen: Boolean, val galleryCloud: Boolean)
