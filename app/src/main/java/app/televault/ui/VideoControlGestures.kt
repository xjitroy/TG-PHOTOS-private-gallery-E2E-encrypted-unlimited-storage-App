package app.televault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Vertical ownership starts after touch slop. Horizontal paging and two-finger zoom stay free. */
internal fun Modifier.videoControlGestures(enabled: Boolean, identity: String, begin: (Offset, Float) -> Unit, drag: (Float) -> Unit, end: (Boolean) -> Unit): Modifier = composed {
    val latestBegin by rememberUpdatedState(begin)
    val latestDrag by rememberUpdatedState(drag)
    val latestEnd by rememberUpdatedState(end)
    pointerInput(enabled, identity) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var movement = Offset.Zero
            var dragging = false
            var completed = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    movement += change.position - change.previousPosition
                    if (!dragging && movement.getDistance() > viewConfiguration.touchSlop) {
                        if (abs(movement.y) <= abs(movement.x) * 1.15f) break
                        dragging = true
                        latestBegin(down.position, size.height.toFloat())
                    }
                    if (dragging) {
                        change.consume()
                        latestDrag(movement.y)
                    }
                    if (!change.pressed) {
                        completed = true
                        break
                    }
                }
            } finally {
                if (dragging) latestEnd(completed)
            }
        }
    }
}

@Composable
internal fun VideoGestureIndicator(volume: Boolean, percent: Int?, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(90)), exit = fadeOut(tween(120))) {
        Column(Modifier.width(52.dp).background(Color.Black.copy(alpha = .62f), RoundedCornerShape(18.dp)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = if (volume) label("Volume", "ভলিউম") else label("Brightness", "উজ্জ্বলতা")
            Icon(if (volume) Icons.Outlined.VolumeUp else Icons.Outlined.Brightness6, title, Modifier.size(20.dp), tint = Color.White)
            Box(Modifier.width(3.dp).height(58.dp).background(Color.White.copy(alpha = .25f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(((percent ?: 0) / if (volume) 200f else 100f).coerceIn(0f, 1f)).background(Color.White, RoundedCornerShape(3.dp)))
            }
            Text(percent?.let { "$it%" } ?: "—", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}
