package app.televault.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/** Reuses the visible frame's display list; no bitmap, disk cache or extra image decoding. */
internal class GalleryBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

@Composable internal fun rememberGalleryBackdrop(): GalleryBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GalleryBackdrop(layer) }
}

internal fun Modifier.captureGallery(backdrop: GalleryBackdrop): Modifier = onGloballyPositioned {
    backdrop.origin = it.positionInRoot()
}.drawWithContent {
    backdrop.layer.record { this@drawWithContent.drawContent() }
    drawLayer(backdrop.layer)
}

@Composable internal fun Modifier.galleryBehind(backdrop: GalleryBackdrop): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }.drawWithContent {
        translate(backdrop.origin.x - origin.x, backdrop.origin.y - origin.y) { drawLayer(backdrop.layer) }
        drawContent()
    }
}
