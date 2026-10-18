package app.televault.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.MediaFacts
import app.televault.data.SharedMedia
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaViewer(entry: MediaEntry, entries: List<MediaEntry>, model: DriveViewModel, close: () -> Unit, choose: (MediaEntry) -> Unit, save: (MediaEntry) -> Unit, trash: (MediaEntry) -> Unit, restore: (MediaEntry) -> Unit, purge: (MediaEntry) -> Unit, favorite: (MediaEntry) -> Unit, backup: (MediaEntry, Boolean) -> Unit = { _, _ -> }, removeDevice: (MediaEntry) -> Unit = {}, profile: () -> Unit = {}, personProfile: (String) -> Unit = {}) {
    var closing by remember { mutableStateOf(false) }
    val finishClose by rememberUpdatedState(close)
    val entrance = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(closing) {
        if (closing) {
            if (entrance.value > 0f) entrance.animateTo(0f, tween(110))
            finishClose()
        } else {
            entrance.animateTo(1f, tween(180))
        }
    }
    val requestClose = { closing = true }
    val context = LocalContext.current
    val activity = remember(context) { videoActivity(context) }
    val pipVisible by (activity?.videoPipVisible ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsStateWithLifecycle()
    val videoSession = remember(model) { ViewerPlaybackSession(context, model) }
    DisposableEffect(videoSession) { onDispose { videoSession.release() } }
    val offlineIds by model.offlineIds.collectAsStateWithLifecycle()
    val snapshot by model.contentState.collectAsStateWithLifecycle()
    val people by model.people.state.collectAsStateWithLifecycle()
    var faceTag by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val initial = remember { app.televault.domain.ViewerOrder.ensureSelected(entries, entry) { it.id } }
    val initialById = remember { initial.associateBy { it.id } }
    val stableIds = remember { initial.map { it.id } }
    val latest = remember(entries) { entries.associateBy { it.id } }
    val cloudById = remember(snapshot.files) { snapshot.files.associateBy { it.id } }
    val resolvedSources = remember { mutableMapOf<String, Uri>() }
    val editedAudio = remember { mutableStateMapOf<String, MediaEntry>() }
    val editedById = editedAudio.toMap()
    val media = remember(latest, cloudById, editedById) { stableIds.mapNotNull { id -> editedById[id] ?: (latest[id] ?: initialById[id])?.let { value -> value.copy(cloud = cloudById[id] ?: value.cloud) } }.ifEmpty { listOf(entry) } }
    val videos = remember(media) { media.filter { it.mime.startsWith("video/") } }
    var scrubbing by remember { mutableStateOf(false) }
    var navigation by remember { mutableStateOf<Job?>(null) }
    val pager = rememberPagerState(initialPage = media.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)) { media.size }
    var videoFullscreen by remember { mutableStateOf(false) }
    var fullscreenExit by remember { mutableIntStateOf(0) }
    var vertical by remember { mutableStateOf(entry.mime.startsWith("video/") && app.televault.domain.VideoPresentation.shortLayout(entry.cloud?.facts?.width ?: entry.local?.width ?: 0, entry.cloud?.facts?.height ?: entry.local?.height ?: 0, 1f)) }
    var photoVertical by remember { mutableStateOf(false) }
    var portraitPage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var controls by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<Uri?>(null) }
    var preparedId by remember { mutableStateOf<String?>(null) }
    val current = media.getOrElse(pager.settledPage) { entry }
    // Keep the action panel's geometry stable under the finger while crossing mixed media/tags.
    var settledChrome by remember { mutableStateOf(current) }
    SideEffect { if (!scrubbing) settledChrome = current }
    val chrome = if (scrubbing) settledChrome else current
    val currentLive by rememberUpdatedState(current)
    var lensConfirm by remember { mutableStateOf<MediaEntry?>(null) }
    var textReading by remember { mutableStateOf(false) }
    var textConsent by remember { mutableStateOf(false) }
    var textPhotoMode by remember { mutableStateOf(false) }
    var textResult by remember { mutableStateOf<Pair<app.televault.ocr.OcrText, Uri>?>(null) }
    fun readText(onPhoto: Boolean) {
        val item = currentLive
        if (textReading)return
        if (!item.mime.startsWith("image/") || item.cloud?.trashedAt?.let { it > 0 } == true || item.local?.trashed == true)return
        textPhotoMode = onPhoto
        if (!model.ocr.state.value.enabled) {
            textConsent = true
            return
        }
        val epoch = model.playbackEpoch
        textReading = true
        controls = true
        message = "Reading text on this phone…"
        scope.launch {
            try {
                val result = model.photoText(item, preferCached = onPhoto)
                val source = item.local?.uri ?: model.preferredDeviceSource(item.id) ?: model.openMedia(item.id)
                if (model.playbackEpoch == epoch && currentLive.id == item.id) {
                    textResult = result to source
                    message = ""
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: LinkageError) {
                message = "On-device OCR is unavailable on this device. Original photos are unchanged."
            } catch (_: OutOfMemoryError) {
                model.ocr.pauseEngine()
                message = "Text reading paused to free memory. Try again with a smaller photo."
            } catch (_: Exception) {
                message = "Text could not be read. Check language download/access and retry from Search."
            } finally {
                textReading = false
            }
        }
    }
    faceTag?.let { fromId ->
        val from = people.people.find { it.id == fromId }
        if (from != null) {
            AlertDialog(onDismissRequest = { faceTag = null }, title = { Text(label("Correct this face tag", "ফেস-ট্যাগ ঠিক করুন")) }, text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(label("Move this photo's tag to the correct person:", "ছবির ট্যাগ সঠিক ব্যক্তিতে সরান:"))
                    TextButton({
                        model.peopleAction { model.people.separateTag(fromId, currentLive.id) }
                        faceTag = null
                    }) { Text(label("Create separate person", "আলাদা ব্যক্তির গ্রুপ করুন")) }
                    people.people.filter { it.id != fromId }.forEach { into ->
                        TextButton({
                            model.peopleAction { model.people.moveTag(fromId, into.id, currentLive.id) }
                            faceTag = null
                        }) {
                            PersonPortrait(into, Modifier.size(32.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(into.name.ifBlank { label("Unnamed person", "নাম দেওয়া হয়নি") })
                        }
                    }
                }
            }, confirmButton = {
                TextButton({
                    model.peopleAction { model.people.untag(fromId, currentLive.id) }
                    faceTag = null
                }) { Text(label("Remove wrong tag", "ভুল ট্যাগ সরান")) }
            }, dismissButton = { TextButton({ faceTag = null }) { Text(label("Cancel", "বাতিল")) } })
        }
    }
    if (textConsent) OcrEnableDialog(model, { textConsent = false }) { readText(textPhotoMode) }
    textResult?.let { (result, source) -> PhotoTextSelection(result, source, textPhotoMode) { textResult = null } }
    lensConfirm?.let { item ->
        AlertDialog(onDismissRequest = { lensConfirm = null }, icon = { Icon(Icons.Outlined.CenterFocusStrong, null) }, title = { Text(label("Open in Google Lens?", "Google Lens-এ খুলবেন?")) }, text = { Text(label("This sends the selected photo to another app. Google may process it online under its privacy policy. Private Vault is never sent.", "নির্বাচিত ছবিটি অন্য অ্যাপে যাবে। Google তাদের গোপনীয়তা নীতি অনুযায়ী অনলাইনে প্রসেস করতে পারে। Private Vault কখনো পাঠানো হবে না।")) }, confirmButton = {
            TextButton({
                lensConfirm = null
                try {
                    check(item.mime.startsWith("image/") && currentLive.id == item.id)
                    val uri = model.shareMedia(item.local?.uri, item.cloud?.id, originalMediaName(item.name), item.mime, item.size)
                    val intent = Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Selected photo", uri) }
                    try {
                        context.startActivity(Intent(intent).setPackage("com.google.ar.lens"))
                    } catch (_: android.content.ActivityNotFoundException) {
                        try {
                            context.startActivity(Intent(intent).setPackage("com.google.android.googlequicksearchbox"))
                        } catch (_: android.content.ActivityNotFoundException) {
                            message = "Install Google Lens or the Google app, then try again."
                        }
                    }
                } catch (_: Exception) {
                    message = "Could not open Google Lens. Unlock and try again."
                }
            }) { Text(label("Continue", "চালিয়ে যান")) }
        }, dismissButton = { TextButton({ lensConfirm = null }) { Text(label("Cancel", "বাতিল")) } })
    }
    val photoLoadToken = remember(current.id, scrubbing) { if (!scrubbing && current.mime.startsWith("image/")) model.beginPhotoLoad(current.id) else 0L }
    DisposableEffect(photoLoadToken) {
        onDispose { if (photoLoadToken != 0L) model.finishPhotoLoad(photoLoadToken) }
    }
    val preparedSource = prepared.takeIf { preparedId == current.id }
    val trashed = current.cloud?.trashedAt?.let { it > 0 } ?: (current.local?.trashed == true)
    // Catalog restore appends pages. It must not navigate back to the entry while browsing.
    LaunchedEffect(entry.id) {
        val index = media.indexOfFirst { it.id == entry.id }
        if (index >= 0 && index != pager.currentPage) {
            pager.animateScrollToPage(index, animationSpec = androidx.compose.animation.core.tween(150))
        }
    }
    LaunchedEffect(pager.settledPage, scrubbing) {
        videoSession.visible(if (!scrubbing && current.mime.startsWith("video/")) "${current.id}|${current.size}|${current.date}" else null)
        if (!scrubbing) videoFullscreen = current.mime.startsWith("video/") && app.televault.domain.VideoPresentation.shortLayout(current.cloud?.facts?.width ?: current.local?.width ?: 0, current.cloud?.facts?.height ?: current.local?.height ?: 0, 1f)
        menu = false
        details = false
    }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress, portraitPage, photoVertical, scrubbing, closing) {
        if (pager.isScrollInProgress || scrubbing || closing) return@LaunchedEffect
        vertical = if (current.mime.startsWith("video/")) {
            portraitPage?.takeIf { it.first == current.id }?.second ?: app.televault.domain.VideoPresentation.shortLayout(current.cloud?.facts?.width ?: current.local?.width ?: 0, current.cloud?.facts?.height ?: current.local?.height ?: 0, 1f)
        } else {
            photoVertical
        }
        if (preparedId != current.id) {
            prepared = null
            preparedId = null
        }
        message = ""
        controls = true
        if (current.cloud != null && !trashed) {
            try {
                model.recordMediaView(current.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { }
        }
        listOf(pager.currentPage, pager.currentPage + 1, pager.currentPage - 1, pager.currentPage + 2, pager.currentPage - 2).mapNotNull { media.getOrNull(it) }.filter { it.cloud != null && (it.id == current.id || it.mime.startsWith("image/")) }.forEach { item ->
            launch {
                try {
                    model.prefetch(item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            }
        }
    }
    fun navigate(index: Int, animate: Boolean = true) {
        if (index !in media.indices || closing) return
        navigation?.cancel()
        navigation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (animate && kotlin.math.abs(index - pager.currentPage) <= 2) pager.animateScrollToPage(index, animationSpec = tween(150)) else pager.scrollToPage(index)
        }
    }
    fun select(item: MediaEntry) {
        navigate(media.indexOfFirst { it.id == item.id })
    }
    fun send(action: String) {
        val item = current
        try {
            val mime = if (!item.mime.startsWith("image/") && !item.mime.startsWith("video/") && !item.mime.startsWith("audio/")) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.name.substringAfterLast('.').lowercase(Locale.ROOT)) ?: item.mime else item.mime
            val uri = model.shareMedia(item.local?.uri, item.cloud?.id, originalMediaName(item.name), mime, item.size)
            val intent = Intent(action).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                clipData = ClipData.newRawUri(item.name, uri)
                if (action == Intent.ACTION_SEND) {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = mime
                }
            }
            context.startActivity(Intent.createChooser(intent, if (action == Intent.ACTION_VIEW) "Open with another app" else "Share original"))
        } catch (_: Exception) {
            message = "Could not open a compatible app. Unlock and try again."
        }
    }
    val viewerMenu: @Composable () -> Unit = {
        DropdownMenu(menu, { menu = false }) {
            if (preparedSource != null && (current.mime.startsWith("image/") || current.mime.startsWith("video/"))) {
                DropdownMenuItem(text = { Text(label("Edit a copy", "কপি এডিট")) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
                    menu = false
                    videoSession.pause("${current.id}|${current.size}|${current.date}")
                    editing = true
                })
            }
            if (current.mime.startsWith("image/")) {
                DropdownMenuItem(text = { Text(if (vertical) label("Horizontal browsing", "পাশাপাশি দেখুন") else label("Vertical browsing", "উপর-নিচে দেখুন")) }, onClick = {
                    photoVertical = !vertical
                    vertical = photoVertical
                    menu = false
                })
            }
            if (trashed) {
                DropdownMenuItem(text = { Text(label("Restore", "ফিরিয়ে আনুন")) }, leadingIcon = { Icon(Icons.Outlined.Restore, null) }, onClick = {
                    menu = false
                    restore(current)
                })
                DropdownMenuItem(text = { Text(label("Delete permanently", "স্থায়ীভাবে মুছুন")) }, leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) }, onClick = {
                    menu = false
                    purge(current)
                })
            } else {
                if (current.local != null) {
                    DropdownMenuItem(text = { Text(label("Backup", "ব্যাকআপ")) }, leadingIcon = { Icon(Icons.Outlined.CloudUpload, null) }, onClick = {
                        menu = false
                        backup(current, false)
                    })
                    if (current.local != null) {
                        DropdownMenuItem(text = { Text(label("Backup and delete from device", "ব্যাকআপ করে ডিভাইস থেকে সরান")) }, onClick = {
                            menu = false
                            backup(current, true)
                        })
                    }
                }
                if (current.local != null) {
                    DropdownMenuItem(text = { Text(label("Upload another copy", "আরেকটি কপি আপলোড করুন")) }, onClick = {
                        menu = false
                        model.upload(listOf(current.local.uri), forceCopy = true)
                    })
                }
                if (current.cloud != null) {
                    DropdownMenuItem(text = { Text(if (current.id in offlineIds) label("Remove offline copy", "অফলাইন কপি সরান") else label("Keep encrypted offline", "এনক্রিপ্টেড অফলাইন রাখুন")) }, leadingIcon = { Icon(Icons.Outlined.OfflinePin, null) }, onClick = {
                        menu = false
                        if (current.id in offlineIds) model.removeOffline(setOf(current.id)) else model.pinOffline(setOf(current.id))
                    })
                    DropdownMenuItem(text = { Text(label("Remove backed-up device copy", "ব্যাকআপ হওয়া ডিভাইস কপি সরান")) }, onClick = {
                        menu = false
                        removeDevice(current)
                    })
                }
                DropdownMenuItem(text = { Text(label("Move to trash", "ট্র্যাশে রাখুন")) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = {
                    menu = false
                    trash(current)
                })
            }
        }
    }
    val movieDetails: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(current.date) + " · ${pager.settledPage + 1}/${media.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (current.cloud != null) label("Encrypted cloud", "এনক্রিপ্টেড ক্লাউড") else label("On device", "ফোনে"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ details = true }) { Icon(Icons.Outlined.Info, label("Details", "বিস্তারিত")) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, label("More", "আরও")) }
                    viewerMenu()
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MovieActionChip(Icons.Outlined.Share, label("Share", "শেয়ার"), !busy) { send(Intent.ACTION_SEND) }
                val loved = current.cloud?.favorite ?: (current.id in snapshot.localFavorites)
                MovieActionChip(if (loved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, label("Favorite", "প্রিয়"), !busy) { favorite(current) }
                MovieActionChip(Icons.Outlined.OpenInNew, label("Open with", "অন্য অ্যাপে"), !busy) { send(Intent.ACTION_VIEW) }
                if (current.cloud != null) MovieActionChip(Icons.Outlined.Download, label("Download", "ডাউনলোড"), !busy) { save(current) }
                MovieActionChip(if (trashed) Icons.Outlined.Restore else Icons.Outlined.DeleteOutline, if (trashed) label("Restore", "ফিরিয়ে আনুন") else label("Trash", "ট্র্যাশ"), !busy) { if (trashed) restore(current) else trash(current) }
            }
        }
    }
    Dialog(onDismissRequest = {
        when {
            menu -> menu = false
            details -> details = false
            videoFullscreen && !vertical -> fullscreenExit++
            else -> requestClose()
        }
    }, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOff, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val dialogView = androidx.compose.ui.platform.LocalView.current
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            // This dialog owns its fade/scale; a second platform exit animation adds latency.
            DisposableEffect(window) {
                window?.setWindowAnimations(0)
                onDispose { }
            }
            val photoLayout = current.mime.startsWith("image/")
            val videoLayout = current.mime.startsWith("video/")
            DisposableEffect(window, pipVisible) {
                window?.decorView?.visibility = if (pipVisible) android.view.View.GONE else android.view.View.VISIBLE
                onDispose { window?.decorView?.visibility = android.view.View.VISIBLE }
            }
            val immersive = videoFullscreen || photoLayout && !controls
            val lightVideoSurface = MaterialTheme.colorScheme.surface.luminance() > .5f
            DisposableEffect(window, immersive, videoLayout, lightVideoSurface) {
                val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, dialogView) }
                val previousStatusIcons = controller?.isAppearanceLightStatusBars
                val previousNavigationIcons = controller?.isAppearanceLightNavigationBars
                if (videoLayout) {
                    controller?.isAppearanceLightStatusBars = false
                    controller?.isAppearanceLightNavigationBars = !immersive && lightVideoSurface
                }
                if (immersive) {
                    controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                } else {
                    controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
                onDispose {
                    controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    if (previousStatusIcons != null) controller?.isAppearanceLightStatusBars = previousStatusIcons
                    if (previousNavigationIcons != null) controller?.isAppearanceLightNavigationBars = previousNavigationIcons
                }
            }
            BackHandler(!videoFullscreen || vertical) {
                when {
                    menu -> menu = false
                    details -> details = false
                    else -> requestClose()
                }
            }
            val renderMedia: @Composable (Modifier) -> Unit = { area ->
                Box(area.background(Color.Black)) {
                    val page: @Composable (Int) -> Unit = { index ->
                        MediaPage(media[index], index == pager.settledPage && !scrubbing, !scrubbing && !closing && !snapshot.uploadSettings.dataSaver && !pager.isScrollInProgress && model.canPrefetchPhotos(), model, { if (index == pager.settledPage && photoLoadToken != 0L) model.finishPhotoLoad(photoLoadToken) }, {
                            resolvedSources[media[index].id] = it
                            if (media[index].id == current.id) {
                                prepared = it
                                preparedId = media[index].id
                            }
                        }, videos, ::select, { direction -> app.televault.domain.ViewerOrder.adjacent(media, index, direction)?.let(::select) }, vertical, { portrait -> if (index == pager.settledPage) portraitPage = media[index].id to portrait }, videoSession, fullscreenExit, { if (index == pager.settledPage) videoFullscreen = it }, { controls = !controls }, if (vertical) null else ({ details = true }), { favorite(media[index]) }, { send(Intent.ACTION_SEND) }, { send(Intent.ACTION_VIEW) }, { cleanup -> backup(media[index], cleanup) }, { removeDevice(media[index]) }, snapshot.backedDeviceFiles, { copy -> editedAudio[stableIds[index]] = copy }, profile, resolvedSources[media[index].id], requestClose, movieDetails, { if (index == pager.settledPage) readText(true) }, scrubbing, { visible -> if (index == pager.settledPage && !scrubbing) controls = visible })
                    }
                    if (vertical) VerticalPager(pager, key = { media[it].id }, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !busy && !scrubbing && !closing) { page(it) } else HorizontalPager(pager, key = { media[it].id }, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !busy && !scrubbing && !closing) { page(it) }
                }
            }
            Surface(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = entrance.value
                    scaleX = .98f + .02f * entrance.value
                    scaleY = scaleX
                },
                color = if (photoLayout) Color.Black else MaterialTheme.colorScheme.background
            ) {
                Box(Modifier.fillMaxSize()) {
                    renderMedia(Modifier.fillMaxSize())
                    Column(if (videoFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()) {
                        AnimatedVisibility(controls && !videoLayout && !pager.isScrollInProgress, enter = fadeIn(), exit = fadeOut()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(requestClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, label("Back", "ফিরুন")) }
                                Column(Modifier.weight(1f)) {
                                    Text(SimpleDateFormat("d MMMM", Locale.getDefault()).format(current.date) + " · ${pager.currentPage + 1}/${media.size}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(originalMediaName(current.name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (current.mime.startsWith("image/") && !trashed) {
                                    IconButton({ lensConfirm = current }) { Icon(Icons.Outlined.CenterFocusStrong, label("Google Lens", "Google Lens")) }
                                    IconButton({ readText(false) }) { Icon(Icons.Outlined.TextFields, label("Text in photo", "ছবির লেখা")) }
                                }
                                IconButton({ details = true }) { Icon(Icons.Outlined.Info, label("Photo information", "ছবির তথ্য")) }
                                Box {
                                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, label("More", "আরও")) }
                                    viewerMenu()
                                }
                            }
                        }
                        if (busy && !current.mime.startsWith("image/") && !current.mime.startsWith("video/")) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!videoLayout && message.isNotBlank()) Text(message, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        AnimatedVisibility(controls, enter = fadeIn(tween(100)), exit = fadeOut(tween(100))) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (media.size > 1) {
                                    ViewerFilmstrip(media, pager, model, !busy && !closing, { dragging ->
                                        if (dragging) navigation?.cancel()
                                        scrubbing = dragging
                                    }, ::navigate, Modifier.fillMaxWidth())
                                }
                                if (chrome.mime.startsWith("video/")) {
                                    Spacer(Modifier.height(104.dp).then(if (videoFullscreen) Modifier.navigationBarsPadding() else Modifier))
                                } else {
                                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .9f), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)), tonalElevation = 1.dp) {
                                        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                                            val tags = people.people.filter { chrome.id in it.members }
                                            if (tags.isNotEmpty()) {
                                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    tags.forEach { person ->
                                                        InputChip(false, { personProfile(person.id) }, label = { Text(person.name.ifBlank { label("Add name", "নাম দিন") }) }, avatar = { PersonPortrait(person, Modifier.size(28.dp)) }, trailingIcon = { IconButton({ faceTag = person.id }, Modifier.size(28.dp)) { Icon(Icons.Outlined.MoreVert, label("Edit face tag", "ফেস-ট্যাগ বদলান"), Modifier.size(18.dp)) } })
                                                    }
                                                }
                                            }
                                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                ViewerAction(Icons.Outlined.Share, label("Share", "শেয়ার"), !busy && !scrubbing && !closing) { send(Intent.ACTION_SEND) }
                                                if (chrome.mime.startsWith("image/")) ViewerAction(Icons.Outlined.Edit, label("Edit", "এডিট"), preparedSource != null && !busy && !scrubbing && !closing) { editing = true }
                                                ViewerAction(Icons.Outlined.OpenInNew, label("Open with", "অন্য অ্যাপে"), !busy && !scrubbing && !closing) { send(Intent.ACTION_VIEW) }
                                                if (chrome.cloud != null) ViewerAction(Icons.Outlined.Download, label("Download", "ডাউনলোড"), !busy && !scrubbing && !closing) { save(chrome) }
                                                val loved = chrome.cloud?.favorite ?: (chrome.id in snapshot.localFavorites)
                                                ViewerAction(if (loved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, label("Favorite", "প্রিয়"), !busy && !scrubbing && !closing, if (loved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface) { favorite(chrome) }
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                                Icon(if (chrome.cloud != null) Icons.Outlined.CloudDone else Icons.Outlined.PhoneAndroid, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                                Text(if (chrome.cloud != null) label(" Encrypted cloud", " এনক্রিপ্টেড ক্লাউড") else label(" On device", " ফোনে"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                TextButton({ details = true }, enabled = !busy && !scrubbing && !closing) { Text(label("Details", "বিস্তারিত")) }
                                                IconButton({ if (trashed) restore(chrome) else trash(chrome) }, enabled = !busy && !scrubbing && !closing) { Icon(if (trashed) Icons.Outlined.Restore else Icons.Outlined.DeleteOutline, if (trashed) label("Restore", "ফিরিয়ে আনুন") else label("Move to Trash", "ট্র্যাশে রাখুন"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (details) MediaInformation(current, preparedSource) { details = false }
                        if (editing && preparedSource != null) MediaEditorSheet(current, preparedSource, model, { editing = false }) { copy -> editedAudio[stableIds[pager.settledPage]] = copy }
                    }
                }
            }
        }
    }
}

@Composable private fun MovieActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, enabled: Boolean, action: () -> Unit) {
    AssistChip(onClick = action, label = { Text(title) }, enabled = enabled, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = 48.dp))
}

@Composable private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, enabled: Boolean, tint: Color = MaterialTheme.colorScheme.onSurface, action: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .9f else 1f, spring(stiffness = 500f), label = "Viewer action")
    Column(Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, onClick = action).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            title,
            Modifier.size(24.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            tint = if (enabled) tint else tint.copy(alpha = .35f)
        )
        Text(title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = if (enabled) tint else tint.copy(alpha = .35f))
    }
}

@Composable private fun MediaPage(item: MediaEntry, active: Boolean, preload: Boolean, model: DriveViewModel, pixelsReady: () -> Unit, ready: (Uri) -> Unit, videos: List<MediaEntry>, choose: (MediaEntry) -> Unit, adjacent: (Int) -> Unit, verticalPaging: Boolean, portraitChanged: (Boolean) -> Unit, videoSession: ViewerPlaybackSession, fullscreenExit: Int, fullScreenChanged: (Boolean) -> Unit, tap: () -> Unit, info: (() -> Unit)?, favorite: () -> Unit, share: () -> Unit, openWith: () -> Unit, backup: (Boolean) -> Unit, removeDevice: () -> Unit, backedDeviceFiles: Map<String, String>, editedCopy: (MediaEntry) -> Unit, profile: () -> Unit, cachedSource: Uri?, closeViewer: () -> Unit, movieDetails: @Composable () -> Unit, longHold: () -> Unit, previewOnly: Boolean, controlsChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    var uri by remember(item.id) { mutableStateOf(cachedSource) }
    var deviceSource by remember(item.id) { mutableStateOf(false) }
    val deviceIndex by model.localMedia.changes.collectAsStateWithLifecycle()
    val deviceCandidates = rememberBackgroundProjection(listOf(model.playbackEpoch, item.id, item.size, item.cloud?.sourceUri, item.cloud?.sourceToken), deviceIndex to backedDeviceFiles, emptyList<String>()) { (index, links) ->
        val cloud = item.cloud
        if (cloud == null) {
            emptyList()
        } else {
            buildList {
                index.forEachIndexed { position, local ->
                    if (position % 128 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (local.size == item.size && (local.uri.toString() == cloud.sourceUri || local.fingerprint == cloud.sourceToken || links[local.fingerprint] == item.id)) add(local.fingerprint)
                }
            }
        }
    }.value
    var retry by remember(item.id) { mutableIntStateOf(0) }
    var error by remember(item.id) { mutableStateOf(false) }
    val warmImage = item.mime.startsWith("image/") && (item.local != null || preload && item.size <= 16L * 1024 * 1024)
    LaunchedEffect(item.id, active, warmImage, retry, deviceCandidates, previewOnly) {
        if (!previewOnly && (active || warmImage)) {
            error = false
            try {
                if (!active) model.awaitPhotoLoad(item.id)
                val localCopy = item.local?.let { local ->
                    if (retry == 0 && (item.mime.startsWith("video/") || item.mime.startsWith("audio/"))) app.televault.data.LocalFileActions.playbackSource(context, local) else local.uri
                } ?: model.preferredDeviceSource(item.id)
                val source = localCopy ?: uri?.takeUnless { deviceSource } ?: if (item.mime.startsWith("video/") || item.mime.startsWith("audio/")) Uri.parse("tgphotos://media/${item.id}") else model.openMedia(item.id)
                deviceSource = localCopy != null
                uri = source
                ready(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
                pixelsReady()
            }
        } else if (!item.mime.startsWith("image/")) {
            uri = null
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().background(Color.Black).then(if (!item.mime.startsWith("image/") && !item.mime.startsWith("video/")) Modifier.safeDrawingPadding().padding(top = 76.dp, bottom = 176.dp) else Modifier), contentAlignment = Alignment.Center) {
        val source = uri
        when {
            previewOnly && (source == null || !item.mime.startsWith("image/")) -> MediaThumbnail(item, model, Modifier.fillMaxSize(), 768, ContentScale.Fit)
            error -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label("Could not open. Check connection, space and file integrity.", "খোলা যায়নি। সংযোগ, জায়গা ও ফাইলের অখণ্ডতা পরীক্ষা করুন।"))
                TextButton({ retry++ }) { Text(label("Retry", "আবার চেষ্টা করুন")) }
            }
            source == null || (!active && !item.mime.startsWith("image/")) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MediaThumbnail(item, model, Modifier.fillMaxSize(), 384, ContentScale.Fit)
                if (active && item.local == null && !item.mime.startsWith("image/") && !item.mime.startsWith("video/")) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label("Opening original…", "মূল ফাইল খুলছে…"), Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item.mime.startsWith("image/") -> key(item.id, source, item.local?.fingerprint) { RegionImage(source, Modifier.fillMaxSize(), tap = tap, swipeUp = info, placeholder = { MediaThumbnail(item, model, Modifier.fillMaxSize(), 384, ContentScale.Fit) }, pixelsReady = pixelsReady, cache = model.photoPreviews, cacheKey = item.id + ":" + (item.local?.fingerprint ?: item.cloud?.hash.orEmpty()), longHold = if (active) longHold else null) }
            item.mime.startsWith("audio/") -> AudioPlayer(source, item, model, editedCopy, share)
            item.mime.startsWith("video/") -> VideoPlayer(source, true, videos, choose, model = model, current = item, session = videoSession, adjacent = adjacent, verticalPaging = verticalPaging, portraitChanged = portraitChanged, openWith = openWith, fullScreenChanged = fullScreenChanged, fullscreenExit = fullscreenExit, favorite = favorite, share = share, backup = backup, removeDevice = removeDevice, profile = profile, closeViewer = closeViewer, movieDetails = movieDetails, controlsChanged = controlsChanged)
            else -> DocumentViewer(source, item, model, openWith)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaInformation(item: MediaEntry, source: Uri?, close: () -> Unit) {
    val context = LocalContext.current
    var facts by remember(item.id) { mutableStateOf(item.cloud?.facts ?: MediaFacts()) }
    var loading by remember(item.id) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val locationAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) reload++ }
    val storedCloudFacts = item.cloud?.facts?.takeIf { it != MediaFacts() }
    val needsRead = source != null && source.scheme != "tgphotos" && (source.authority != "${context.packageName}.private-media" || storedCloudFacts == null)
    val reads = remember(item.id, source, reload, needsRead) { if (needsRead) app.televault.data.MediaReadSession.open(context, checkNotNull(source)) else null }
    DisposableEffect(reads) { onDispose { reads?.close() } }
    LaunchedEffect(item.id, source, reload, reads) {
        if (reads != null) {
            loading = true
            try {
                val read = withContext(Dispatchers.IO) { MediaFacts.read(context, reads, item.mime) }
                ensureActive()
                facts = if (read.width > 0 || read.camera.isNotBlank() || read.durationMs > 0) read else facts
            } finally {
                loading = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(label("Details", "বিস্তারিত"), style = MaterialTheme.typography.headlineSmall)
            Text(item.name, Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val unavailable = label("Not recorded in this file", "এই ফাইলে তথ্য নেই")
            InfoRow(Icons.Outlined.PhotoCamera, label("Captured on", "যে ক্যামেরায় তোলা"), facts.camera.ifBlank { unavailable })
            InfoRow(Icons.Outlined.Schedule, label("Capture date and time", "তোলার তারিখ ও সময়"), if (facts.capturedAt > 0) SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault()).format(facts.capturedAt) else unavailable)
            InfoRow(Icons.Outlined.CalendarMonth, label("Timeline date", "টাইমলাইনের তারিখ"), SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault()).format(item.date))
            InfoRow(Icons.Outlined.AspectRatio, label("Dimensions · aspect ratio", "আকার · অনুপাত"), if (facts.width > 0 && facts.height > 0) "${facts.width} × ${facts.height} · ${facts.aspect}" else unavailable)
            InfoRow(Icons.Outlined.Description, label("Format · size", "ফরম্যাট · সাইজ"), item.mime + " · " + sizeLabel(item.size))
            if (facts.durationMs > 0) InfoRow(Icons.Outlined.PlayCircle, label("Duration", "দৈর্ঘ্য"), "${facts.durationMs / 60000}:${"%02d".format(facts.durationMs / 1000 % 60)}")
            InfoRow(Icons.Outlined.LocationOn, label("Location", "লোকেশন"), if (facts.latitude != null && facts.longitude != null) String.format(Locale.US, "%.6f, %.6f", facts.latitude, facts.longitude) else label("Not recorded, or restricted by Android", "তথ্য নেই, বা Android গোপন করেছে"))
            if (facts.latitude != null && facts.longitude != null) TextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${facts.latitude},${facts.longitude}?q=${facts.latitude},${facts.longitude}"))) } }) { Text(label("View on map", "ম্যাপে দেখুন")) }
            if (item.local != null && item.mime.startsWith("image/") && android.os.Build.VERSION.SDK_INT >= 29 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) TextButton({ locationAccess.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION) }) { Text(label("Allow photo-location metadata", "ছবির লোকেশনের তথ্যের অনুমতি দিন")) }
            InfoRow(if (item.cloud != null) Icons.Outlined.CloudDone else Icons.Outlined.PhoneAndroid, label("Stored in", "যেখানে রয়েছে"), if (item.cloud != null) label("Encrypted cloud · ", "এনক্রিপ্টেড ক্লাউড · ") + item.cloud.album else item.local?.folder.orEmpty())
            Text(label("Camera and location are shown only when embedded. The upload phone is not treated as the camera.", "ক্যামেরা ও লোকেশন ফাইলে থাকলেই দেখানো হয়। আপলোডের ফোনকে ক্যামেরা হিসেবে দেখানো হয় না।"), Modifier.padding(vertical = 18.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
