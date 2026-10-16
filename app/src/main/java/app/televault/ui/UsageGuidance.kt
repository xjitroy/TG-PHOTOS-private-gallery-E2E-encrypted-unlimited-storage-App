package app.televault.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Observes unsupported gestures without consuming a valid pager or control gesture. */
internal fun Modifier.observeWrongSwipe(vertical: Boolean, failed: () -> Unit): Modifier = pointerInput(vertical) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var end = down.position
        var multipleTouches = false
        var lifted = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) multipleTouches = true
            val touch = event.changes.firstOrNull { it.id == down.id }
            if (touch != null && touch.previousPressed && !touch.pressed) lifted = true
            if (touch != null) end = touch.position
        } while (event.changes.any { it.pressed })
        val delta = end - down.position
        if (vertical && lifted && !multipleTouches && abs(delta.x) > 64.dp.toPx() && abs(delta.x) > abs(delta.y) * 1.5f) failed()
    }
}

/** Two repeated failures, bounded frequency, three lifetime hints per topic/Drive; no tracking. */
@Composable
internal fun UsageGuidance(model: DriveViewModel, topic: String, failures: Int, english: String, bengali: String, modifier: Modifier = Modifier) {
    val enabled by model.preferences.guidanceEnabled.collectAsStateWithLifecycle(initialValue = true)
    var visible by remember(topic) { mutableStateOf(false) }
    var lastAt by remember(topic) { mutableLongStateOf(0) }
    LaunchedEffect(failures, enabled) {
        if (!enabled) {
            visible = false
            return@LaunchedEffect
        }
        if (failures < 2 || failures % 2 != 0 || visible) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        if (lastAt != 0L && now - lastAt < 30_000) return@LaunchedEffect
        if (model.preferences.claimGuide(model.guidanceScope, topic)) {
            lastAt = now
            visible = true
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            delay(6500)
            visible = false
        }
    }
    AnimatedVisibility(visible && enabled, modifier) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), tonalElevation = 4.dp) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TouchApp, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label(english, bengali), Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.bodySmall)
                IconButton({ visible = false }) { Icon(Icons.Outlined.Close, label("Dismiss guide", "নির্দেশনা বন্ধ")) }
            }
        }
    }
}
