package app.televault.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.televault.DriveViewModel
import app.televault.data.LocalMedia
import app.televault.data.MediaEditCopies
import java.util.UUID
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MediaEditorSheet(item: MediaEntry, prepared: Uri, model: DriveViewModel, close: () -> Unit, saved: (MediaEntry) -> Unit, external: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = item.mime.startsWith("image/")
    var source by remember(item.id) { mutableStateOf<Uri?>(null) }
    var original by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var preview by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var rotation by remember { mutableIntStateOf(0) }
    var ratio by remember { mutableFloatStateOf(0f) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var warmth by remember { mutableFloatStateOf(0f) }
    var mirror by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    var compareOriginal by remember { mutableStateOf(false) }
    var exportPixels by remember { mutableLongStateOf(4_000_000) }
    var exportHeight by remember { mutableIntStateOf(0) }
    var preciseTrim by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf(0f..1f) }
    var duration by remember { mutableLongStateOf(0L) }
    var mute by remember { mutableStateOf(false) }
    var gain by remember { mutableFloatStateOf(1f) }
    var grayscale by remember { mutableStateOf(false) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var exportProgress by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    LaunchedEffect(item.id) {
        try {
            val uri = if (prepared.scheme == "tgphotos") model.openMedia(item.id) else prepared
            source = uri
            if (image) {
                original = MediaEditCopies.image(context, uri)
            } else {
                duration = withContext(Dispatchers.IO) {
                    val reader = android.media.MediaMetadataRetriever()
                    try {
                        reader.setDataSource(context, uri)
                        val width = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val height = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rotated = (reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 180 != 0
                        withContext(Dispatchers.Main) {
                            videoWidth = if (rotated) height else width
                            videoHeight = if (rotated) width else height
                        }
                        reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        reader.release()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure = "This file cannot be edited with the supported device codec. Original unchanged."
        }
    }
    LaunchedEffect(original, rotation, ratio, exposure, saturation, contrast, warmth, mirror, caption) {
        val bitmap = original ?: return@LaunchedEffect
        delay(120)
        preview = withContext(Dispatchers.Default) { MediaEditCopies.adjust(bitmap, rotation, ratio, exposure, saturation, contrast, warmth, mirror, caption) }
    }
    val extension = if (image) "png" else "mp4"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (image) "image/png" else "video/mp4")) { uri ->
        val input = source
        val bitmap = preview
        if (uri != null && input != null && (!image || bitmap != null)) {
            scope.launch {
                busy = true
                failure = ""
                try {
                    if (image) {
                        if (exportPixels <= 4_000_000) {
                            MediaEditCopies.saveImage(context, input, uri, checkNotNull(bitmap))
                        } else {
                            withContext(Dispatchers.Default) {
                                val detail = MediaEditCopies.image(context, input, exportPixels)
                                try {
                                    val rendered = MediaEditCopies.adjust(detail, rotation, ratio, exposure, saturation, contrast, warmth, mirror, caption)
                                    try {
                                        MediaEditCopies.saveImage(context, input, uri, rendered)
                                    } finally {
                                        rendered.eraseColor(0)
                                        rendered.recycle()
                                    }
                                } finally {
                                    detail.recycle()
                                }
                            }
                        }
                    } else if (!preciseTrim && ratio == 0f && exposure == 0f && !grayscale && gain == 1f && contrast == 1f && warmth == 0f && !mirror && exportHeight == 0) {
                        MediaEditCopies.saveVideo(context, input, uri, (range.start * duration).toLong(), (range.endInclusive * duration).toLong(), rotation, mute)
                    } else {
                        app.televault.data.VideoEditExport.save(context, input, uri, (range.start * duration).toLong(), (range.endInclusive * duration).toLong(), rotation, ratio, videoWidth, videoHeight, exposure, grayscale, gain, mute, contrast, warmth, mirror, exportHeight) { exportProgress = it }
                    }
                    val now = System.currentTimeMillis()
                    val local = withContext(Dispatchers.IO) {
                        val document = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                        LocalMedia(uri, document?.name ?: "Edited copy.$extension", if (image) "image/png" else "video/mp4", document?.length() ?: 0, now, now, "Edited copies", addedAt = now)
                    }
                    model.localMedia.refresh()
                    saved(MediaEntry(uri.toString(), local.name, local.mime, local.size, now, local = local))
                    close()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: OutOfMemoryError) {
                    failure = "Not enough memory for this export. Choose Balanced photo detail or a lower video resolution and retry. Original unchanged."
                } catch (_: Exception) {
                    failure = "Could not export. Choose a writable local folder and supported format. Original unchanged."
                } finally {
                    busy = false
                }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) close() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 660.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (image) label("Edit photo", "ছবি এডিট") else label("Edit video copy", "ভিডিও কপি এডিট"), style = MaterialTheme.typography.titleLarge)
            Text(label("The original is never replaced. Saved copies stay local until you back them up.", "মূল ফাইল কখনো বদলাবে না। ব্যাকআপ না করা পর্যন্ত সেভ করা কপি লোকাল থাকবে।"), style = MaterialTheme.typography.bodySmall)
            if (failure.isNotEmpty()) Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (image) {
                (if (compareOriginal) original else preview)?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit) }
                Text(label("Preview uses a memory-safe copy. Choose Balanced (up to 4 MP) or High detail (up to 16 MP) for the saved PNG. Original stays unchanged.", "প্রিভিউতে মেমোরি নিয়ন্ত্রণে ছোট কপি ব্যবহৃত হয়। PNG সেভে Balanced (সর্বোচ্চ ৪ MP) বা High detail (সর্বোচ্চ ১৬ MP) বাছুন। মূল ফাইল অক্ষত থাকবে।"), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(label("Before / after", "আগে / পরে"), Modifier.weight(1f))
                    Switch(compareOriginal, { compareOriginal = it })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(exportPixels == 4_000_000L, { exportPixels = 4_000_000 }, enabled = !busy, label = { Text("Balanced · 4 MP") })
                    FilterChip(exportPixels == 16_000_000L, { exportPixels = 16_000_000 }, enabled = !busy, label = { Text("High detail · 16 MP") })
                }
                OutlinedTextField(caption, { caption = it.take(120) }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(label("Caption", "ছবিতে লেখা")) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0f to "Fit", 1f to "1:1", 4f / 3f to "4:3", 16f / 9f to "16:9").forEach { (value, title) -> FilterChip(ratio == value, { ratio = value }, enabled = !busy, label = { Text(title) }) }
                }
                Text(label("Brightness", "উজ্জ্বলতা"))
                Slider(exposure, { exposure = it }, valueRange = -1f..1f, enabled = !busy)
                Text(label("Color", "রং"))
                Slider(saturation, { saturation = it }, valueRange = 0f..2f, enabled = !busy)
            } else {
                source?.let { VideoEditPreview(it, duration, range, rotation, ratio, videoWidth, videoHeight, exposure, grayscale, gain, mute, contrast, warmth, mirror, exportHeight) }
                Text(label("Trim and rotate keep encoded tracks when possible. Crop, color or volume exports an H.264/AAC copy using your device codec; subtitles are not exported.", "সম্ভব হলে trim ও rotate-এ encoded track রাখা হয়। Crop, রং বা volume বদলালে ফোনের codec দিয়ে H.264/AAC কপি সেভ হবে; সাবটাইটেল export হয় না।"), style = MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0f to "Fit", 1f to "1:1", 9f / 16f to "9:16", 16f / 9f to "16:9").forEach { (value, title) -> FilterChip(ratio == value, { ratio = value }, enabled = !busy && (value == 0f || videoWidth > 0 && videoHeight > 0), label = { Text(title) }) }
                }
                Text(label("Brightness", "উজ্জ্বলতা"))
                Slider(exposure, { exposure = it }, valueRange = -1f..1f, enabled = !busy)
                Row {
                    Checkbox(grayscale, { grayscale = it }, enabled = !busy)
                    Text(label("Black & white", "সাদা-কালো"))
                }
                Text(label("Volume", "ভলিউম") + " · ${(gain * 100).toInt()}%")
                Slider(gain, { gain = it }, valueRange = 0f..2f, enabled = !busy && !mute)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Source", 720 to "720p", 1080 to "1080p").forEach { (height, title) -> FilterChip(exportHeight == height, { exportHeight = height }, enabled = !busy, label = { Text(title) }) }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(preciseTrim, { preciseTrim = it }, enabled = !busy)
                    Text(label("Precise trim · re-encode", "নির্ভুল trim · নতুন করে encode"))
                }
                Text("${(range.start * duration / 1000).toLong()}s – ${(range.endInclusive * duration / 1000).toLong()}s")
                RangeSlider(range, { range = it }, enabled = !busy && duration > 0)
                Row {
                    Checkbox(mute, { mute = it }, enabled = !busy)
                    Text(label("Remove audio from copy", "কপি থেকে অডিও সরান"))
                }
            }
            Text(label("Contrast", "কনট্রাস্ট"))
            Slider(contrast, { contrast = it }, valueRange = 0f..2f, enabled = !busy)
            Text(label("Warmth", "রঙের উষ্ণতা"))
            Slider(warmth, { warmth = it }, valueRange = -1f..1f, enabled = !busy)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(mirror, { mirror = it }, enabled = !busy)
                Text(label("Flip horizontally", "ডান-বাম উল্টান"))
            }
            TextButton({
                rotation = 0
                ratio = 0f
                exposure = 0f
                saturation = 1f
                contrast = 1f
                warmth = 0f
                mirror = false
                caption = ""
                range = 0f..1f
                gain = 1f
                mute = false
                grayscale = false
                compareOriginal = false
            }, enabled = !busy) { Text(label("Reset adjustments", "সব পরিবর্তন রিসেট")) }
            if (external != null) {
                TextButton(external, enabled = !busy) {
                    Icon(Icons.Outlined.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text(label("Edit in another app", "অন্য অ্যাপে এডিট"))
                }
            }
            OutlinedButton({ rotation = (rotation + 1) % 4 }, enabled = !busy) {
                Icon(Icons.Outlined.RotateRight, null)
                Text(" ${rotation * 90}°")
            }
            Button({
                val name = item.name.substringBeforeLast('.', item.name).take(80) + "_edited_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.$extension"
                save.launch(name)
            }, Modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = !busy && source != null && (if (image) preview != null else duration > 0 && (range.endInclusive - range.start) * duration >= 250)) { Text(if (busy) label("Saving copy…", "কপি সেভ হচ্ছে…") + exportProgress?.let { " $it%" }.orEmpty() else label("Save as copy", "কপি হিসেবে সেভ")) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoEditPreview(source: Uri, duration: Long, range: ClosedFloatingPointRange<Float>, rotation: Int, ratio: Float, width: Int, height: Int, brightness: Float, grayscale: Boolean, gain: Float, mute: Boolean, contrast: Float, warmth: Float, mirror: Boolean, exportHeight: Int) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val player = remember(source) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(source))
            prepare()
        }
    }
    var previewFailure by remember(source) { mutableStateOf(false) }
    LaunchedEffect(player, rotation, ratio, width, height, brightness, grayscale, gain, mute, contrast, warmth, mirror, exportHeight) {
        delay(120)
        val effects = app.televault.data.VideoEditExport.effects(rotation, ratio.takeIf { width > 0 && height > 0 } ?: 0f, width, height, brightness, grayscale, gain, contrast, warmth, mirror, exportHeight)
        previewFailure = runCatching { player.setVideoEffects(effects.videoEffects) }.isFailure
        player.volume = if (mute) 0f else gain.coerceAtMost(1f)
    }
    DisposableEffect(player, owner) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                previewFailure = true
            }
        }
        player.addListener(listener)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = {
        androidx.media3.ui.PlayerView(it).apply {
            this.player = player
            useController = true
        }
    }, modifier = Modifier.fillMaxWidth().height(180.dp), onRelease = { it.player = null })
    if (previewFailure) Text(label("This device cannot preview these video effects. The original is preserved.", "এই ফোনে এই ভিডিও effect preview করা যাচ্ছে না। মূল ফাইল অক্ষত।"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    TextButton({
        previewFailure = false
        val clip = androidx.media3.common.MediaItem.Builder().setUri(source).setClippingConfiguration(androidx.media3.common.MediaItem.ClippingConfiguration.Builder().setStartPositionMs((range.start * duration).toLong()).setEndPositionMs((range.endInclusive * duration).toLong()).build()).build()
        player.setMediaItem(clip)
        player.prepare()
        player.play()
    }, enabled = duration > 0 && range.endInclusive > range.start) { Text(label("Preview selected range", "নির্বাচিত অংশ প্রিভিউ")) }
}
