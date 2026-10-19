package app.televault.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.PreviewSequence
import app.televault.domain.ScrubPreviewQuality

@Composable
internal fun ScrubMediaPreview(item: MediaEntry, model: DriveViewModel, onReady: (() -> Unit)? = null, onDetailReady: (() -> Unit)? = null) {
    val previewKey = item.id + ":" + (item.local?.fingerprint ?: item.cloud?.hash.orEmpty())
    val revision by model.photoPreviews.revision.collectAsStateWithLifecycle()
    val generation = remember(revision) { model.photoPreviews.epoch() }
    val available = remember(previewKey, revision) { model.photoPreviews.get(previewKey) }
    // Neighbor cache churn must not replace displayed sharp pixels with a small tile.
    var retained by remember(previewKey, generation) { mutableStateOf(available) }
    val previous = retained
    val frame = if (available != null && (previous == null || app.televault.domain.ThumbnailQuality.preferNew(previous.bitmap.width, previous.bitmap.height, available.bitmap.width, available.bitmap.height))) available else previous
    SideEffect { if (frame != null) retained = frame }
    val ready by rememberUpdatedState(onReady)
    val detailReady by rememberUpdatedState(onDetailReady)
    LaunchedEffect(frame) {
        if (frame != null) {
            ready?.invoke()
            detailReady?.invoke()
        }
    }
    if (frame != null) {
        DecodedPhotoImage(frame)
    } else {
        key(item.id) { MediaThumbnail(item, model, Modifier.fillMaxSize(), if (item.mime.startsWith("image/")) 1024 else 384, ContentScale.Fit, onReady = onReady) }
    }
}

@Composable
internal fun DecodedPhotoImage(frame: app.televault.data.DecodedPhotoCache.Frame, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val rect = remember { RectF() }
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        val bitmap = frame.bitmap
        val quarterTurn = frame.orientation in 5..8
        val x = size.width / (if (quarterTurn) bitmap.height else bitmap.width)
        val y = size.height / (if (quarterTurn) bitmap.width else bitmap.height)
        val factor = if (contentScale == ContentScale.Crop) maxOf(x, y) else minOf(x, y)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        try {
            canvas.translate(size.width / 2f, size.height / 2f)
            canvas.rotate(ScrubPreviewQuality.rotation(frame.orientation).toFloat())
            if (ScrubPreviewQuality.mirrored(frame.orientation)) canvas.scale(-1f, 1f)
            rect.set(-bitmap.width * factor / 2f, -bitmap.height * factor / 2f, bitmap.width * factor / 2f, bitmap.height * factor / 2f)
            canvas.drawBitmap(bitmap, null, rect, paint)
        } finally {
            canvas.restore()
        }
    }
}

private data class ScrubWarmRequest(val item: MediaEntry, val adjacent: Boolean, val allowCloud: Boolean)

/** Decode the requested photo first, then half-area screen-fit neighbors, without moving the pager. */
@Composable
internal fun PrepareScrubPreviews(media: List<MediaEntry>, index: Int, scrubbing: Boolean, enabled: Boolean, model: DriveViewModel) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val width = (configuration.screenWidthDp * density).toInt().coerceAtLeast(1)
    val height = (configuration.screenHeightDp * density).toInt().coerceAtLeast(1)
    val scope = rememberCoroutineScope()
    val pipeline = remember(model, width, height) {
        PreviewSequence<ScrubWarmRequest>(scope) { request, owner ->
            val token = if (!request.adjacent) model.beginPhotoLoad(request.item.id) else 0L
            try {
                model.scrubPreview(request.item, width, height, request.adjacent, request.allowCloud, owner)
            } finally {
                if (token != 0L) model.finishPhotoLoad(token)
            }
        }
    }
    DisposableEffect(pipeline) { onDispose { pipeline.close() } }
    val neighbors = remember(media, index) { listOf(index, index + 1, index - 1, index + 2, index - 2).mapNotNull { media.getOrNull(it) } }
    val identity = neighbors.map { it.id + ":" + (it.local?.fingerprint ?: it.cloud?.hash.orEmpty()) }
    val epoch = model.playbackEpoch
    LaunchedEffect(pipeline, identity, index, scrubbing, enabled, epoch) {
        pipeline.close()
        if (!enabled) return@LaunchedEffect
        val current = media.getOrNull(index) ?: return@LaunchedEffect
        if (!scrubbing) model.awaitPhotoLoad(current.id)
        val cloudWarm = model.canPrefetchPhotos()
        pipeline.request(
            neighbors.filter { it.mime.startsWith("image/") }.mapNotNull {
                val adjacent = it.id != current.id
                if (!scrubbing && !adjacent) null else ScrubWarmRequest(it, adjacent, !adjacent || cloudWarm && it.size <= 16L * 1024 * 1024)
            }
        )
    }
}
