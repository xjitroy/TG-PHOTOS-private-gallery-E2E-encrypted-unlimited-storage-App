package app.televault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.televault.DriveViewModel

/** No original URI/decoder/player is opened for a transient strip position. */
@Composable
internal fun ScrubMediaPreview(item: MediaEntry, model: DriveViewModel) {
    val previewKey = item.id + ":" + (item.local?.fingerprint ?: item.cloud?.hash.orEmpty())
    // Region cache stores raw EXIF-oriented pixels. Other orientations use the already
    // oriented thumbnail path, rather than showing a sideways/mirrored cached preview.
    val frame = remember(previewKey) { model.photoPreviews.get(previewKey)?.takeIf { it.orientation == 1 } }
    if (frame != null) {
        Image(frame.bitmap.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    } else {
        key(item.id) { MediaThumbnail(item, model, Modifier.fillMaxSize(), 384, ContentScale.Fit) }
    }
}
