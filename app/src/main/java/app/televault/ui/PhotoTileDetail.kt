package app.televault.ui

import androidx.compose.runtime.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.PreviewRequestOwner
import app.televault.domain.ThumbnailQuality
import kotlinx.coroutines.CancellationException

/** Only composed large photo tiles request source detail; never waits for scroll to stop. */
@Composable
internal fun PhotoTileDetail(item: MediaEntry, model: DriveViewModel, size: IntSize, needed: Boolean) {
    val key = "tile:" + item.id + ":" + (item.local?.fingerprint ?: item.cloud?.hash.orEmpty())
    val access = model.playbackEpoch
    val selected by model.selectedPhoto.collectAsStateWithLifecycle(initialValue = null)
    val canWork = selected == null
    val revision by model.photoPreviews.revision.collectAsStateWithLifecycle()
    val generation = remember(revision) { model.photoPreviews.epoch() }
    val available = remember(key, revision, access) { model.photoPreviews.get(key) }
    var retained by remember(key, generation, access) { mutableStateOf(available) }
    val previous = retained
    val frame = if (available != null && (previous == null || ThumbnailQuality.preferNew(previous.bitmap.width, previous.bitmap.height, available.bitmap.width, available.bitmap.height))) available else previous
    SideEffect { retained = frame }
    val owner = remember(key, size, needed, access, canWork) { PreviewRequestOwner() }
    DisposableEffect(owner) { onDispose { owner.close() } }
    LaunchedEffect(owner) {
        if (!needed || !canWork) return@LaunchedEffect
        try {
            model.tilePreview(item, size.width, size.height, owner)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Keep the catalog/provider thumbnail when a source is unavailable.
        } catch (_: OutOfMemoryError) {
            // No immediate allocation retry, and never discard already painted pixels.
        } finally {
            owner.close()
        }
    }
    if (frame != null) DecodedPhotoImage(frame, contentScale = ContentScale.Crop)
}
