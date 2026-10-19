package app.televault.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** A thin visible timeline with the native Slider's full touch, keyboard and accessibility behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSeekBar(value: Float, buffered: Float, enabled: Boolean, changed: (Float) -> Unit, finished: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val thumbSize by animateDpAsState(if (dragging) 16.dp else 12.dp, tween(100), label = "Video seek thumb")
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .4f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val description = label("Video position", "ভিডিওর সময়")
    Slider(value.coerceIn(0f, 1f), changed, enabled = enabled, onValueChangeFinished = finished, interactionSource = interaction, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = description }, thumb = {
        Box(Modifier.size(thumbSize).background(accent, CircleShape))
    }, track = {
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val start = Offset(if (rtl) size.width else 0f, center.y)
            fun point(fraction: Float) = Offset(if (rtl) size.width * (1 - fraction) else size.width * fraction, center.y)
            drawLine(Color.White.copy(alpha = .22f), start, point(1f), strokeWidth = size.height, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = .5f), start, point(buffered.coerceIn(0f, 1f)), strokeWidth = size.height, cap = StrokeCap.Round)
            drawLine(accent, start, point(value.coerceIn(0f, 1f)), strokeWidth = size.height, cap = StrokeCap.Round)
        }
    })
}
