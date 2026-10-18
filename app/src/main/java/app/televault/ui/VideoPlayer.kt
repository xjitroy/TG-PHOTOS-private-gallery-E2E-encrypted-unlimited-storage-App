package app.televault.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.televault.DriveViewModel
import app.televault.domain.VideoPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoPlayer(uri: Uri, video: Boolean, videos: List<MediaEntry>, choose: (MediaEntry) -> Unit, model: DriveViewModel, current: MediaEntry, session: ViewerPlaybackSession, modifier: Modifier = Modifier, fullScreenChanged: (Boolean) -> Unit = {}, adjacent: (Int) -> Unit = {}, verticalPaging: Boolean = false, portraitChanged: (Boolean) -> Unit = {}, openWith: () -> Unit = {}, fullscreenExit: Int = 0, favorite: () -> Unit = {}, share: () -> Unit = {}, backup: (Boolean) -> Unit = {}, removeDevice: () -> Unit = {}, profile: () -> Unit = {}, closeViewer: () -> Unit = {}, movieDetails: @Composable () -> Unit = {}, controlsChanged: (Boolean) -> Unit = {}) {
    val identity = remember(current.id, current.size, current.date) { "${current.id}|${current.size}|${current.date}" }
    val epoch = remember(identity) { model.playbackEpoch }
    var resume by remember(identity) { mutableStateOf<Long?>(if (session.owns(identity)) 0L else null) }
    LaunchedEffect(identity, epoch) {
        if (session.owns(identity)) return@LaunchedEffect
        resume = try {
            model.playbackPosition(identity, epoch)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            0L
        }
    }
    if (resume != null) {
        ReadyVideoPlayer(uri, video, videos, choose, model, current, modifier, session, fullScreenChanged, adjacent, verticalPaging, portraitChanged, openWith, fullscreenExit, favorite, share, backup, removeDevice, profile, identity, epoch, resume!!, closeViewer, movieDetails, controlsChanged)
    } else {
        Box(modifier.fillMaxSize().background(Color.Black)) { MediaThumbnail(current, model, Modifier.fillMaxSize(), 384, androidx.compose.ui.layout.ContentScale.Fit) }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ReadyVideoPlayer(uri: Uri, video: Boolean, videos: List<MediaEntry>, choose: (MediaEntry) -> Unit, model: DriveViewModel, current: MediaEntry, modifier: Modifier, session: ViewerPlaybackSession, fullScreenChanged: (Boolean) -> Unit, adjacent: (Int) -> Unit, verticalPaging: Boolean, portraitChanged: (Boolean) -> Unit, openWith: () -> Unit, fullscreenExit: Int, favorite: () -> Unit, share: () -> Unit, backup: (Boolean) -> Unit, removeDevice: () -> Unit, profile: () -> Unit, identity: String, epoch: Long, resume: Long, closeViewer: () -> Unit, movieDetails: @Composable () -> Unit, controlsChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val activity = remember(context) { videoActivity(context) }
    val pipVisible by (activity?.videoPipVisible ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsStateWithLifecycle()
    val player = remember(uri, identity) { session.prepare(uri, identity, resume) }
    val scope = rememberCoroutineScope()
    val subtitlePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null && session.owns(identity)) {
            scope.launch {
                try {
                    val title = withContext(Dispatchers.IO) {
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, selected)?.name.orEmpty()
                    }
                    if (!session.owns(identity) || epoch != model.playbackEpoch) return@launch
                    val mime = when (title.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
                        "srt" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                        "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
                        "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
                        else -> null
                    }
                    if (mime == null) model.report("Choose a supported .srt, .vtt, .ass or .ssa subtitle file.") else session.subtitle(identity, selected, mime, title.take(512))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    if (session.owns(identity) && epoch == model.playbackEpoch) model.report("Could not attach these subtitles. Video position is retained.")
                }
            }
        }
    }

    val snapshot by model.playerState.collectAsStateWithLifecycle()
    val onAdjacent by rememberUpdatedState(adjacent)
    val onPortraitChanged by rememberUpdatedState(portraitChanged)
    val initialWidth = current.cloud?.facts?.width ?: current.local?.width ?: 0
    val initialHeight = current.cloud?.facts?.height ?: current.local?.height ?: 0
    var frameRatio by remember(identity) { mutableFloatStateOf(if (initialWidth > 0 && initialHeight > 0) initialWidth * player.videoSize.pixelWidthHeightRatio / initialHeight else 16f / 9f) }
    LaunchedEffect(player, identity) { if (session.owns(identity)) player.play() }
    var frameKnown by remember(identity) { mutableStateOf(initialWidth > 0 && initialHeight > 0) }
    var short by remember(uri) { mutableStateOf(verticalPaging || VideoPresentation.shortLayout(initialWidth, initialHeight, 1f)) }

    var wrongSwipes by remember { mutableIntStateOf(0) }
    var actionsVisible by remember(uri) { mutableStateOf(true) }
    var bufferedPercent by remember(uri) { mutableIntStateOf(0) }
    var notes by remember(uri) { mutableStateOf(false) }
    var note by remember(uri) { mutableStateOf("") }
    var noteLoading by remember(uri) { mutableStateOf(false) }
    fun openNote() {
        notes = true
        noteLoading = true
        scope.launch {
            try {
                note = model.privateNote(current.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                notes = false
                model.report("Could not read the private note. Unlock and try again; the saved note is unchanged.")
            } finally {
                noteLoading = false
            }
        }
    }
    val suggestions = remember(videos, current.id) { videos.filter { it.id != current.id && (it.cloud?.trashedAt == 0L || it.local?.trashed == false) }.sortedByDescending { it.date }.distinctBy { "${originalMediaName(it.name)}|${it.size}|${it.date}" } }
    val isFavorite = current.cloud?.favorite == true || current.id in snapshot.localFavorites
    val zoom = remember(identity) { MediaZoomState(4f) }
    var view by remember(uri) { mutableStateOf<PlayerView?>(null) }
    DisposableEffect(pipVisible, player, view) {
        if (!pipVisible && session.owns(identity)) {
            view?.player = null
            view?.player = player
        }
        onDispose {}
    }
    var fullscreen by remember(identity) { mutableStateOf(short) }
    var repeat by remember { mutableStateOf(player.repeatMode == Player.REPEAT_MODE_ONE) }
    var fit by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var playerError by remember { mutableStateOf("") }
    var buffering by remember { mutableStateOf(true) }
    var showBuffering by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(fullscreenExit) { if (fullscreenExit > 0 && !short) fullscreen = false }
    BackHandler(fullscreen && !short) { fullscreen = false }
    var settings by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("") }
    var badge by remember { mutableStateOf(true) }
    var badgeEpoch by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    val onControlsChanged by rememberUpdatedState(controlsChanged)
    LaunchedEffect(controls) { onControlsChanged(controls) }
    val actionsBottom by animateDpAsState(if (controls) 170.dp else 54.dp, tween(180), label = "Short action position")
    var playbackPosition by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    val indicatorAllowed = app.televault.domain.MediaLoadingPolicy.mayShowIndicator(current.mime, maxOf(duration, current.cloud?.facts?.durationMs ?: current.local?.durationMs ?: 0L))
    LaunchedEffect(buffering, uri, indicatorAllowed) {
        showBuffering = false
        if (buffering && indicatorAllowed) {
            delay(app.televault.domain.MediaLoadingPolicy.VIDEO_INDICATOR_DELAY)
            showBuffering = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var seekDraft by remember(uri) { mutableStateOf<Float?>(null) }
    var seekNotice by remember(uri) { mutableStateOf("") }
    var seekNoticeEpoch by remember(uri) { mutableIntStateOf(0) }
    var controlEpoch by remember { mutableIntStateOf(0) }
    val seekUnavailable = label("This video is still preparing, or its format has no seek index.", "ভিডিও এখনও প্রস্তুত হচ্ছে, অথবা এই ফরম্যাটে seek index নেই।")
    val seekPositionLabel = label("Position: ", "সময়: ")
    fun seekVideo(target: Long) {
        val length = player.duration
        if (!session.owns(identity)) return
        if (length <= 0 || !player.isCurrentMediaItemSeekable) {
            seekNotice = seekUnavailable
        } else {
            val bounded = target.coerceIn(0, (length - 1).coerceAtLeast(0))
            if (current.cloud != null) model.markVideoPosition(current.id, bounded, player.duration, epoch)
            player.seekTo(bounded)
            playbackPosition = bounded
            seekNotice = seekPositionLabel + playbackTime(bounded)
        }
        controls = true
        controlEpoch++
        seekNoticeEpoch++
    }
    LaunchedEffect(seekNoticeEpoch) {
        delay(1800)
        seekNotice = ""
    }
    fun savePosition() {
        if (session.owns(identity)) model.savePlaybackPosition(identity, player.currentPosition, player.duration, player.playbackState == Player.STATE_ENDED, epoch)
    }
    LaunchedEffect(player, identity) {
        while (true) {
            delay(5000)
            if (player.isPlaying) savePosition()
        }
    }
    LaunchedEffect(player, owner, identity) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (controls || !player.isPlaying) {
                    playbackPosition = player.currentPosition
                    duration = player.duration.coerceAtLeast(0)
                }
                if (current.cloud != null) model.videoWindow(current.id, player.currentPosition, player.duration)
                if (buffering) bufferedPercent = player.bufferedPercentage.coerceIn(0, 100)
                playing = player.isPlaying
                delay(if (controls || buffering) 250 else 1000)
            }
        }
    }
    LaunchedEffect(short) {
        onPortraitChanged(short)
        if (short) fullscreen = true
        controlEpoch++
    }
    LaunchedEffect(fullscreen, short) { fullScreenChanged(fullscreen || short) }
    LaunchedEffect(identity, fullscreen, short, verticalPaging, frameKnown, pipVisible) {
        session.orientation(identity, VideoPresentation.landscapePlayback(verticalPaging, short, fullscreen, frameKnown), pipVisible)
    }
    LaunchedEffect(controlEpoch, short) {
        delay(if (short) 3000 else 5000)
        controls = false
    }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    DisposableEffect(player, owner, identity) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!session.owns(identity)) return
                duration = player.duration.coerceAtLeast(0)
                playbackPosition = player.currentPosition
                playing = player.isPlaying
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (!session.owns(identity)) return
                if (state == Player.STATE_ENDED) savePosition()
                if (state == Player.STATE_ENDED && short) {
                    if (current.cloud != null) scope.launch { runCatching { model.recordMediaView(current.id) } }
                    if (VideoPresentation.replayAfterEnd(session.completionCount(identity))) {
                        player.seekTo(0)
                        player.play()
                    } else {
                        player.pause()
                        controls = true
                        controlEpoch++
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                playerError = "Playback failed. Check connection, file integrity or device codec support."
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (!session.owns(identity) || size.width <= 0 || size.height <= 0) return
                frameKnown = true
                short = verticalPaging || VideoPresentation.shortLayout(size.width, size.height, size.pixelWidthHeightRatio)
                frameRatio = size.width * size.pixelWidthHeightRatio / size.height
                quality = "${size.width} × ${size.height}"
                badge = true
                badgeEpoch++
            }
            override fun onTracksChanged(value: Tracks) {
                tracks = value
                val f = player.videoFormat
                quality = f?.let { "${it.width} × ${it.height}" }.orEmpty()
                badgeEpoch++
                badge = true
            }
        }
        val lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                savePosition()
                if (session.owns(identity) && activity?.videoPipVisible?.value != true) player.pause()
            }
        }
        buffering = player.playbackState == Player.STATE_BUFFERING
        player.addListener(listener)
        owner.lifecycle.addObserver(lifecycle)
        onDispose {
            savePosition()
            owner.lifecycle.removeObserver(lifecycle)
            player.removeListener(listener)
            view?.player = null
        }
    }
    LaunchedEffect(badgeEpoch) {
        delay(6000)
        badge = false
    }
    val detailsScroll = rememberLazyListState()
    val insets = if (fullscreen || short) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(insets)) {
        val compactHeight by animateDpAsState(VideoPresentation.compactHeight(maxWidth.value, maxHeight.value, frameRatio).dp, tween(220), label = "Compact video size")
        Column(Modifier.fillMaxSize()) {
            Box((if (fullscreen || short) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(if (video) compactHeight else 120.dp)).clipToBounds()) {
                key(uri) {
                    AndroidView(factory = {
                        (android.view.LayoutInflater.from(it).inflate(app.televault.R.layout.tgphotos_video_surface, null, false) as PlayerView).apply {
                            this.player = player
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            controllerShowTimeoutMs = 6000
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                            setShowFastForwardButton(true)
                            setShowRewindButton(true)
                        }.also { view = it }
                    }, update = {
                        val resize = if (fit) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        if (it.resizeMode != resize) it.resizeMode = resize
                        if (it.keepScreenOn != playing) it.keepScreenOn = playing
                        if (it.player !== player) it.player = player
                    }, modifier = Modifier.fillMaxSize().mediaZoomLayer(zoom))
                }
                Box(
                    Modifier.fillMaxSize().mediaZoomInput(zoom).observeWrongSwipe(short && verticalPaging) { wrongSwipes++ }.pointerInput(player, short, identity) {
                        detectTapGestures(onTap = {
                            controls = !controls
                            if (short) actionsVisible = true
                            controlEpoch++
                            if (controls)view?.showController()else view?.hideController()
                            badge = true
                            badgeEpoch++
                        }, onDoubleTap = { point ->
                            seekVideo(player.currentPosition + if (point.x < size.width / 2) -10000 else 10000)
                            controls = true
                            controlEpoch++
                        })
                    }.pointerInput(short, verticalPaging, current.id, fullscreen, frameKnown) {
                        if (verticalPaging) return@pointerInput
                        var dragged = 0f
                        detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
                            if (short && !verticalPaging && kotlin.math.abs(dragged) > 80.dp.toPx()) {
                                onAdjacent(if (dragged < 0) 1 else -1)
                            } else if (!short && frameKnown) {
                                fullscreen = VideoPresentation.fullscreenAfterSwipe(fullscreen, dragged, 72.dp.toPx())
                                controls = true
                                controlEpoch++
                            }
                        }) { change, amount ->
                            if (!verticalPaging) {
                                dragged += amount
                                change.consume()
                            }
                        }
                    }
                )
                UsageGuidance(model, "reels-swipe", wrongSwipes, "Swipe up or down for the next photo or video. Tap to show controls.", "পরের ছবি বা ভিডিওর জন্য উপরে বা নিচে সোয়াইপ করুন। কন্ট্রোল দেখতে ট্যাপ করুন।", Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 110.dp))
                if (showBuffering && current.local == null && playerError.isBlank()) Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .65f)).padding(10.dp)) { CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp) }
                if (seekNotice.isNotBlank()) Text(seekNotice, Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .65f)).padding(12.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
                if (playerError.isNotBlank()) {
                    Surface(Modifier.align(Alignment.Center).padding(16.dp), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(playerError, style = MaterialTheme.typography.bodySmall)
                            TextButton({
                                playerError = ""
                                player.prepare()
                                player.play()
                            }) { Text(label("Retry", "আবার চেষ্টা")) }
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(controls, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)), enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
                    Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f)).padding(horizontal = 12.dp)) {
                        Slider(seekDraft ?: if (duration > 0) (playbackPosition.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f, {
                            seekDraft = it
                            controlEpoch++
                        }, onValueChangeFinished = {
                            seekDraft?.let { seekVideo((it.toDouble() * player.duration.coerceAtLeast(0)).toLong()) }
                            seekDraft = null
                            controlEpoch++
                        }, enabled = duration > 0, valueRange = 0f..1f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({
                                seekVideo(player.currentPosition - 10000)
                                controlEpoch++
                            }) { Icon(Icons.Outlined.Replay10, label("Back 10 seconds", "১০ সেকেন্ড পেছনে"), tint = androidx.compose.ui.graphics.Color.White) }
                            IconButton({
                                if (player.isPlaying) {
                                    player.pause()
                                } else {
                                    if (player.playbackState == Player.STATE_ENDED) {
                                        session.restartCycle(identity)
                                        player.seekTo(0)
                                    }
                                    player.play()
                                }
                                controlEpoch++
                            }) { Icon(if (playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow, label("Play / pause", "চালান / থামান"), tint = androidx.compose.ui.graphics.Color.White) }
                            IconButton({
                                seekVideo(player.currentPosition + 10000)
                                controlEpoch++
                            }) { Icon(Icons.Outlined.Forward10, label("Forward 10 seconds", "১০ সেকেন্ড এগিয়ে"), tint = androidx.compose.ui.graphics.Color.White) }
                            Text("${playbackTime(seekDraft?.let { (it.toDouble() * duration).toLong() } ?: playbackPosition)} / ${playbackTime(duration)}", modifier = Modifier.weight(1f), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (short) {
                    Box(Modifier.align(Alignment.Center).aspectRatio(frameRatio, matchHeightConstraintsFirst = true)) {
                        androidx.compose.animation.AnimatedVisibility(actionsVisible, modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 76.dp, bottom = 20.dp), enter = fadeIn(tween(240)) + slideInHorizontally(tween(240)) { -it / 4 }, exit = fadeOut(tween(240)) + slideOutHorizontally(tween(240)) { -it / 4 }) {
                            Row(
                                Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = .28f)).clickable {
                                    player.pause()
                                    profile()
                                }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ProfileAvatar(snapshot.privateProfile, Modifier.size(42.dp))
                                Column(Modifier.weight(1f, fill = false)) {
                                    Text(snapshot.privateProfile?.name.orEmpty().ifBlank { label("Private profile", "ব্যক্তিগত প্রোফাইল") }, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(current.date)), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(actionsVisible, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = actionsBottom).navigationBarsPadding(), enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(240)), exit = slideOutHorizontally(tween(240)) { it } + fadeOut(tween(240))) {
                            Column(
                                Modifier.pointerInput(uri) {
                                    var dragged = 0f
                                    detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (kotlin.math.abs(dragged) > 24.dp.toPx()) actionsVisible = false }) { change, amount ->
                                        dragged += amount
                                        change.consume()
                                    }
                                }.verticalScroll(rememberScrollState()).background(Color.Black.copy(alpha = .32f), RoundedCornerShape(22.dp)),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                IconButton(favorite) { Icon(if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, label("Favorite", "প্রিয়"), tint = if (isFavorite) Color(0xffff7299) else Color.White) }
                                Text(label("Love", "লাভ"), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                IconButton({
                                    openNote()
                                }) { Icon(Icons.Outlined.ModeComment, label("Private note", "ব্যক্তিগত নোট"), tint = Color.White) }
                                Text(label("Private note", "নোট"), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                IconButton(share) { Icon(Icons.Outlined.Share, label("Share", "শেয়ার"), tint = Color.White) }
                                IconButton(openWith) { Icon(Icons.Outlined.OpenInNew, label("Open with", "অন্য অ্যাপে"), tint = Color.White) }
                                IconButton({ actionsVisible = false }) { Icon(Icons.Outlined.ChevronRight, label("Hide video actions", "ভিডিওর বাটন লুকান"), tint = Color.White) }
                            }
                        }
                        if (!actionsVisible) {
                            Surface(Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = actionsBottom).navigationBarsPadding(), shape = RoundedCornerShape(18.dp), color = Color.Black.copy(alpha = .38f)) {
                                IconButton({ actionsVisible = true }) { Icon(Icons.Outlined.ChevronLeft, label("Show video actions", "ভিডিওর বাটন দেখান"), tint = Color.White) }
                            }
                        }
                    }
                }
                if (badge && video && short && controls && quality.isNotBlank()) Text(quality, Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = 60.dp, top = 14.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .50f)).padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                androidx.compose.animation.AnimatedVisibility(controls, modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)), enter = fadeIn(), exit = fadeOut()) {
                    IconButton({ if (fullscreen && !short) fullscreen = false else closeViewer() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, label("Back", "ফিরুন"), tint = Color.White) }
                }
                androidx.compose.animation.AnimatedVisibility(controls, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)), enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
                    Row(Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(18.dp))) {
                        if (activity?.supportsVideoPiP() == true) {
                            IconButton({
                                if (!activity.enterVideoPiP(player)) model.report("Picture-in-Picture is unavailable. Allow it in Android App settings.")
                            }) { Icon(Icons.Outlined.PictureInPictureAlt, label("Picture-in-Picture", "ছোট উইন্ডোতে ভিডিও"), tint = Color.White) }
                        }
                        IconButton({ settings = true }) { Icon(Icons.Outlined.Settings, label("Playback settings", "প্লেব্যাক সেটিংস"), tint = Color.White) }
                        if (!short) IconButton({ fullscreen = !fullscreen }) { Icon(if (fullscreen)Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, label("Full screen", "ফুল স্ক্রিন"), tint = Color.White) }
                    }
                }
            }
            if (video && !fullscreen && !short) {
                Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = detailsScroll, contentPadding = PaddingValues(bottom = 20.dp)) {
                        item(key = "movie-details") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(originalMediaName(current.name), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                TextButton({
                                    openNote()
                                }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(label("Private encrypted note", "ব্যক্তিগত এনক্রিপ্টেড নোট")) }
                                movieDetails()
                                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                Text(label("More videos · newest first", "আরও ভিডিও · নতুন থেকে পুরোনো"), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        items(suggestions.size, key = { "suggestion:" + suggestions[it].id }) { index ->
                            val item = suggestions[index]
                            ListItem(headlineContent = { Text(originalMediaName(item.name), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }, supportingContent = { Text(sizeLabel(item.size), style = MaterialTheme.typography.labelSmall) }, leadingContent = { MediaThumbnail(item, model, Modifier.width(112.dp).height(64.dp).clip(RoundedCornerShape(12.dp)), 224) }, modifier = Modifier.fillMaxWidth().clickable { choose(item) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
                        }
                        if (suggestions.isEmpty()) item(key = "no-next-video") { Text(label("No other videos in this selection", "এই তালিকায় আর ভিডিও নেই"), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }

    if (notes) {
        AlertDialog(onDismissRequest = { notes = false }, title = { Text(label("Private note", "ব্যক্তিগত নোট")) }, text = {
            Column {
                Text(label("Only on this phone, encrypted with your Drive key. This is not a public comment.", "এই ফোনে Drive কী দিয়ে এনক্রিপ্টেড থাকবে। এটি পাবলিক কমেন্ট নয়।"), style = MaterialTheme.typography.bodySmall)
                if (noteLoading) SecureLoader() else OutlinedTextField(note, { note = it.take(4096) }, maxLines = 6)
            }
        }, confirmButton = {
            TextButton({
                model.savePrivateNote(current.id, note)
                notes = false
            }, enabled = !noteLoading) { Text(label("Save", "সেভ")) }
        }, dismissButton = { TextButton({ notes = false }) { Text(label("Cancel", "বাতিল")) } })
    }
    if (settings) {
        AlertDialog(onDismissRequest = { settings = false }, title = { Text(label("Playback settings", "প্লেব্যাক সেটিংস")) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                TextButton({ zoom.reset() }) { Text(label("Reset zoom", "জুম রিসেট")) }
                if (current.local != null && !current.local.trashed) {
                    TextButton({
                        settings = false
                        backup(false)
                    }) { Text(label("Backup", "ব্যাকআপ")) }
                    if (current.local != null) {
                        TextButton({
                            settings = false
                            backup(true)
                        }) { Text(label("Backup and delete from device", "ব্যাকআপ করে ডিভাইস থেকে সরান")) }
                    }
                }
                if (current.cloud?.trashedAt == 0L) {
                    TextButton({
                        settings = false
                        removeDevice()
                    }) { Text(label("Remove backed-up device copy", "ব্যাকআপ হওয়া ডিভাইস কপি সরান")) }
                }

                Text(label("Speed", "গতি"), style = MaterialTheme.typography.titleSmall)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(.5f, 1f, 1.5f, 2f).forEach { value ->
                        TextButton({
                            speed = value
                            player.setPlaybackSpeed(value)
                        }) { Text(if (speed == value) "✓ $value×" else "$value×") }
                    }
                }
                if (!short) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label("Repeat", "বারবার চালান"), Modifier.weight(1f))
                        Switch(repeat, {
                            repeat = it
                            player.repeatMode = if (it) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        })
                    }
                }
                if (short) Text(label("Short videos play3 times, then stop. Play starts a new3-play session.", "শর্ট ভিডিও ৩ বার চলে থামবে। Play দিলে আবার ৩ বার চলবে।"), style = MaterialTheme.typography.bodySmall)
                if (video) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label("Fill screen (crop)", "স্ক্রিন পূর্ণ (ক্রপ)"), Modifier.weight(1f))
                        Switch(fit, {
                            fit = it
                            zoom.reset()
                        })
                    }
                }
                TextButton({ subtitlePicker.launch(arrayOf("text/*", "application/octet-stream", "application/x-subrip")) }) { Text(label("Choose subtitle file", "সাবটাইটেল ফাইল বাছুন")) }
                listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT).forEach { type ->
                    val groups = tracks.groups.filter { it.type == type }
                    if (groups.isNotEmpty()) {
                        Text(if (type == C.TRACK_TYPE_AUDIO) label("Audio / dubbing language", "অডিও / ডাবিং ভাষা") else label("Subtitles", "সাবটাইটেল"), style = MaterialTheme.typography.titleSmall)
                        TextButton({ player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT).build() }) { Text(if (type == C.TRACK_TYPE_TEXT) label("Off", "বন্ধ") else label("Automatic", "স্বয়ংক্রিয়")) }
                        groups.forEach { group ->
                            (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
                                val format = group.getTrackFormat(index)
                                TextButton({ player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build() }) {
                                    val language = format.language?.takeUnless { it == "und" }?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault()) }?.takeIf { it.isNotBlank() }
                                    val title = listOfNotNull(format.label?.takeIf { it.isNotBlank() }, language).distinct().joinToString(" · ").ifBlank { "Track ${index + 1}" }
                                    Text((if (group.isTrackSelected(index)) "✓ " else "") + title + " · ${index + 1}")
                                }
                            }
                        }
                    }
                }
                Text(label("Available video tracks", "ভিডিওর উপলব্ধ ট্র্যাক"), style = MaterialTheme.typography.titleSmall)
                TextButton({
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                    settings = false
                }) { Text(label("Original / automatic", "মূল / স্বয়ংক্রিয়")) }
                tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
                    (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
                        val format = group.getTrackFormat(index)
                        TextButton({
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                            settings = false
                            badge = true
                            badgeEpoch++
                        }) { Text("${format.width} × ${format.height}") }
                    }
                }
            }
        }, confirmButton = { TextButton({ settings = false }) { Text(label("Close", "বন্ধ")) } })
    }
}

internal fun playbackTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
