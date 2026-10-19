package app.televault.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.televault.DriveViewModel
import app.televault.data.AudioTrim
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AudioPlayer(uri: Uri, item: MediaEntry, model: DriveViewModel, editedCopy: (MediaEntry) -> Unit, share: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var copyUri by remember(uri) { mutableStateOf<Uri?>(null) }
    var copyName by remember(uri) { mutableStateOf("") }
    val currentUri = copyUri ?: uri
    val currentName = copyName.ifBlank { originalMediaName(item.name) }
    val player = remember(currentUri) { ExoPlayer.Builder(context).build() }
    var position by remember(currentUri) { mutableLongStateOf(0) }
    var duration by remember(currentUri) { mutableLongStateOf(0) }
    var playing by remember(currentUri) { mutableStateOf(false) }
    var ready by remember(currentUri) { mutableStateOf(false) }
    var error by remember(currentUri) { mutableStateOf("") }
    var gain by remember(currentUri) { mutableFloatStateOf(1f) }
    var fadeIn by remember(currentUri) { mutableStateOf(false) }
    var fadeOut by remember(currentUri) { mutableStateOf(false) }
    var trim by remember(currentUri) { mutableStateOf(false) }
    var invalidCuts by remember { mutableIntStateOf(0) }
    var range by remember(currentUri) { mutableStateOf(0f..1f) }
    var seek by remember(currentUri) { mutableStateOf<Float?>(null) }
    var speed by remember(currentUri) { mutableFloatStateOf(1f) }
    var previewRange by remember(currentUri) { mutableStateOf<Pair<Long, Long>?>(null) }
    var livePreview by remember(currentUri) { mutableStateOf(true) }
    var previewOffset by remember(currentUri) { mutableLongStateOf(0) }
    var resumeAt by remember(currentUri) { mutableLongStateOf(0) }
    var saving by remember(uri) { mutableStateOf(false) }
    var progress by remember(uri) { mutableFloatStateOf(0f) }
    var status by remember(uri) { mutableStateOf("") }
    var pendingSave by remember(uri) { mutableStateOf<AudioCopyRequest?>(null) }
    fun stopPreview() {
        if (previewRange != null) {
            player.pause()
            resumeAt = position
            previewRange = null
        }
    }
    fun preview() {
        if (duration <= 0 || (range.endInclusive - range.start) * duration < 250) return
        if (previewRange != null) {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        } else {
            previewOffset = 0
            previewRange = (range.start * duration).toLong() to (range.endInclusive * duration).toLong()
        }
    }
    LaunchedEffect(player, previewRange) {
        val factory = if (currentUri.scheme == "tgphotos") model.mediaSourceFactory() else androidx.media3.datasource.DefaultDataSource.Factory(context)
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(currentUri))
        val selection = previewRange
        if (selection != null) {
            player.setMediaSource(ClippingMediaSource(source, selection.first * 1000, selection.second * 1000))
            player.seekTo(previewOffset)
        } else {
            player.setMediaSource(source, resumeAt)
        }
        player.prepare()
        player.playWhenReady = selection != null || !trim
    }
    LaunchedEffect(player, trim, livePreview, saving, pendingSave) {
        if (!trim || !livePreview || saving || pendingSave != null) return@LaunchedEffect
        snapshotFlow { range }.distinctUntilChanged().conflate().collect { selection ->
            val start = (selection.start * duration).toLong()
            val end = (selection.endInclusive * duration).toLong()
            if (end - start >= 250 && previewRange != (start to end)) {
                previewOffset = if (previewRange?.first == start) (end - start - 2000).coerceAtLeast(0) else 0
                previewRange = start to end
            } else if (end - start < 250) {
                stopPreview()
            }
            // Coalesce fast handle movement; never queue a player/source for every touch event.
            delay(250)
        }
    }
    val savedMessage = label("Edited copy saved. Original unchanged. Upload this copy manually if wanted.", "এডিট করা কপি সেভ হয়েছে। মূল ফাইল বদলায়নি। চাইলে কপিটি ম্যানুয়ালি আপলোড করুন।")
    val saveFailedMessage = label("Could not save. Choose a local folder with space; the phone must support this audio codec. Original unchanged.", "সেভ হয়নি। জায়গা আছে এমন লোকাল ফোল্ডার বাছুন; অডিও codec ফোনে সমর্থিত হতে হবে। মূল ফাইল বদলায়নি।")
    val playbackFailedMessage = label("Playback failed. Retry or use Open with.", "চালানো যায়নি। Retry বা Open with ব্যবহার করুন।")
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { destination ->
        val request = pendingSave
        pendingSave = null
        if (destination != null && request != null && !saving) {
            scope.launch {
                saving = true
                progress = 0f
                error = ""
                player.pause()
                var savedCopy: MediaEntry? = null
                try {
                    val source = if (request.source.scheme == "tgphotos") model.openMedia(item.id) else request.source
                    AudioTrim.copy(context, source, destination, request.startMs, request.endMs, request.gain, request.fadeInMs, request.fadeOutMs) { value -> scope.launch { progress = value } }
                    val local = withContext(Dispatchers.IO) {
                        val document = runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, destination) }.getOrNull()
                        val now = System.currentTimeMillis()
                        app.televault.data.LocalMedia(destination, document?.name ?: request.name, "audio/wav", document?.length() ?: 0, now, document?.lastModified()?.takeIf { it > 0 } ?: now, "Edited audio", addedAt = now)
                    }
                    savedCopy = MediaEntry(destination.toString(), local.name, local.mime, local.size, local.date, local = local)
                    copyName = local.name
                    copyUri = destination
                    status = savedMessage
                    model.localMedia.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = saveFailedMessage
                } finally {
                    saving = false
                }
                savedCopy?.let(editedCopy)
            }
        }
    }
    DisposableEffect(player, owner) {
        val failures = object : Player.Listener {
            override fun onPlayerError(failure: PlaybackException) {
                error = playbackFailedMessage
            }
        }
        val lifecycle = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(failures)
        owner.lifecycle.addObserver(lifecycle)
        onDispose {
            owner.lifecycle.removeObserver(lifecycle)
            player.removeListener(failures)
            player.release()
        }
    }
    LaunchedEffect(player, owner, previewRange) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                position = player.currentPosition + (previewRange?.first ?: 0)
                if (previewRange == null && player.duration > 0) duration = player.duration
                playing = player.isPlaying
                val clipped = previewRange
                if (clipped != null) {
                    val length = (clipped.second - clipped.first).coerceAtLeast(1)
                    val at = player.currentPosition.coerceAtLeast(0)
                    val fade = minOf(if (fadeIn) at / 1000f else 1f, if (fadeOut) (length - at).coerceAtLeast(0) / 1000f else 1f).coerceIn(0f, 1f)
                    player.volume = gain.coerceAtMost(1f) * fade
                } else {
                    player.volume = gain.coerceAtMost(1f)
                }
                ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
                delay(if (previewRange != null) 50 else 250)
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppSurface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.GraphicEq, null, Modifier.size(100.dp).padding(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Text(currentName, style = MaterialTheme.typography.titleLarge)
        Text(if (copyUri != null) label("Edited audio copy", "এডিট করা অডিও কপি") else label("Private audio · original quality", "ব্যক্তিগত অডিও · অরিজিনাল কোয়ালিটি"), style = MaterialTheme.typography.bodySmall)

        Slider(seek ?: if (duration > 0) position.toFloat() / duration else 0f, { seek = it }, onValueChangeFinished = {
            seek?.let { player.seekTo((it * duration).toLong()) }
            seek = null
        }, enabled = duration > 0 && !saving && previewRange == null)
        Text("${playbackTime(position)} / ${playbackTime(duration)}", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ player.seekTo((position - 10000).coerceAtLeast(0)) }, enabled = !saving && previewRange == null) { Icon(Icons.Outlined.Replay10, label("Back10 seconds", "১০ সেকেন্ড পেছনে")) }
            FilledIconButton({
                if (trim) {
                    preview()
                } else if (playing) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            }, enabled = ready && !saving, modifier = Modifier.size(60.dp)) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, label("Play / pause", "চালান / থামান")) }
            IconButton({ player.seekTo((position + 10000).coerceAtMost(duration)) }, enabled = duration > 0 && !saving && previewRange == null) { Icon(Icons.Outlined.Forward10, label("Forward10 seconds", "১০ সেকেন্ড এগিয়ে")) }
        }
        FlowRow {
            listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                TextButton({
                    speed = value
                    player.setPlaybackSpeed(value)
                }, enabled = !saving) { Text(if (speed == value) "✓$value×" else "$value×") }
            }
        }
        OutlinedButton({
            stopPreview()
            trim = !trim
            player.pause()
        }, enabled = duration > 0 && !saving) {
            Icon(Icons.Outlined.ContentCut, null)
            Text(if (trim) label("Close editor", "এডিট বন্ধ করুন") else label("Edit audio", "অডিও এডিট করুন"))
        }
        if (previewRange != null) Text(label("Preview plays only the selected range", "প্রিভিউতে শুধু নির্বাচিত অংশটি চলবে"), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(share, enabled = !saving) {
            Icon(Icons.Outlined.Share, null)
            Text(label("Share audio", "অডিও শেয়ার করুন"))
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (saving) {
            SecureLoader()
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall)
            TextButton({
                error = ""
                player.prepare()
                player.play()
            }, enabled = !saving) { Text(label("Retry playback", "আবার চালান")) }
        }
    }
    if (trim) {
        ModalBottomSheet(onDismissRequest = {
            if (!saving) {
                stopPreview()
                trim = false
            }
        }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label("Cut audio · new copy", "অডিও কাটুন · নতুন কপি"), style = MaterialTheme.typography.titleLarge)
                Text("${playbackTime((range.start * duration).toLong())} — ${playbackTime((range.endInclusive * duration).toLong())}", style = MaterialTheme.typography.titleMedium)
                RangeSlider(range, {
                    range = it
                }, onValueChangeFinished = {
                    if ((range.endInclusive - range.start) * duration < 250) invalidCuts++
                }, enabled = !saving && pendingSave == null)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live preview", Modifier.weight(1f))
                    Switch(livePreview, {
                        livePreview = it
                        if (!it) stopPreview()
                    }, enabled = !saving && pendingSave == null)
                }
                Text(label("Volume", "ভলিউম"))
                Slider(gain, {
                    gain = it
                    player.volume = it.coerceAtMost(1f)
                }, valueRange = 0f..2f, enabled = !saving && pendingSave == null)
                Text(label("Export gain: ${(gain * 100).toInt()}%. Preview volume is capped at 100%.", "Export ভলিউম: ${(gain * 100).toInt()}%। Preview সর্বোচ্চ ১০০%।"), style = MaterialTheme.typography.bodySmall)
                FlowRow {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(fadeIn, { fadeIn = it }, enabled = !saving)
                        Text(label("Fade in · 1s", "শুরুতে fade · ১ সেকেন্ড"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(fadeOut, { fadeOut = it }, enabled = !saving)
                        Text(label("Fade out · 1s", "শেষে fade · ১ সেকেন্ড"))
                    }
                }
                Text("Preview follows the selected range and fades. Gain above 100% applies to the saved copy; preview is capped at 100%.", style = MaterialTheme.typography.bodySmall)
                UsageGuidance(model, "audio-cut", invalidCuts, "Drag both handles to select at least a quarter second. Preview it, then Save as copy.", "দুটি হ্যান্ডেল টেনে অন্তত এক-চতুর্থাংশ সেকেন্ড বাছুন। প্রিভিউ শুনে নতুন কপি হিসেবে সেভ করুন।")
                if (previewRange != null) Text(label("Playing selection: ", "নির্বাচিত অংশ চলছে: ") + playbackTime(position), style = MaterialTheme.typography.labelLarge)

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({
                        preview()
                    }, enabled = !saving && (range.endInclusive - range.start) * duration >= 250) { Text(if (previewRange != null && playing) label("Pause preview", "প্রিভিউ থামান") else label("Preview selection", "নির্বাচিত অংশ শুনুন")) }
                    Button({
                        val base = currentName.substringBeforeLast('.').replace(Regex("[\\\\/\\u0000-\\u001f]"), "_").take(100)
                        stopPreview()
                        player.pause()
                        val name = "${base}_edited_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.wav"
                        pendingSave = AudioCopyRequest(currentUri, (range.start * duration).toLong(), (range.endInclusive * duration).toLong(), name, gain, if (fadeIn) 1000L else 0L, if (fadeOut) 1000L else 0L)
                        try {
                            save.launch(name)
                        } catch (_: Exception) {
                            pendingSave = null
                            error = saveFailedMessage
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !saving && pendingSave == null && (range.endInclusive - range.start) * duration >= 250) { Text(label("Save as copy", "নতুন কপি হিসেবে সেভ")) }
                }
                Text(label("New WAV copy only; original is never replaced. Uncompressed audio uses more space. This exported copy is readable in the chosen folder. No automatic cloud upload.", "শুধু নতুন WAV কপি; মূল ফাইল কখনও বদলাবে না। WAV-তে বেশি জায়গা লাগে। নির্বাচিত ফোল্ডারে এই কপিটি সাধারণ অডিও হিসেবে পড়া যাবে। অটো ক্লাউড আপলোড হবে না।"), style = MaterialTheme.typography.bodySmall)
                if (saving) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

private data class AudioCopyRequest(val source: Uri, val startMs: Long, val endMs: Long, val name: String, val gain: Float, val fadeInMs: Long, val fadeOutMs: Long)
