package app.televault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** The saved id locates a normal-library row; it never reopens a viewer or Private Vault. */
internal class BrowseFocusState {
    var pendingId by mutableStateOf<String?>(null)
        private set
    var highlightedId by mutableStateOf<String?>(null)
        private set
    var centeringTail by mutableStateOf(false)
        private set
    var targetBounds by mutableStateOf<Rect?>(null)
    var viewportBounds by mutableStateOf<Rect?>(null)
    var revision by mutableIntStateOf(0)
        private set
    fun request(id: String?) {
        pendingId = id?.takeIf { it.isNotBlank() }
        centeringTail = pendingId != null
        highlightedId = null
        targetBounds = null
        revision++
    }
    fun located(id: String, token: Int) {
        if (token != revision || pendingId != id) return
        pendingId = null
        highlightedId = id
    }
    fun clearHighlight(token: Int) {
        if (token == revision) highlightedId = null
    }
    fun cancel(id: String, token: Int) {
        if (token == revision && pendingId == id) request(null)
    }
}
internal val LocalBrowseFocus = staticCompositionLocalOf<BrowseFocusState?> { null }

@Composable
internal fun LocateBrowseFocus(positions: Map<String, Int>, firstVisible: () -> Int, jump: suspend (Int) -> Unit, adjust: suspend (Float, Int) -> Unit) {
    val focus = LocalBrowseFocus.current ?: return
    val id = focus.pendingId
    val position = positions[id]
    val token = focus.revision
    LaunchedEffect(focus, token, position) {
        if (id == null || position == null) return@LaunchedEffect
        try {
            withFrameNanos { }
            // Distant travel does not animate thousands of intervening rows.
            if (focus.targetBounds == null || kotlin.math.abs(position - firstVisible()) > 12) jump(position)
            withFrameNanos { }
            val bounds = withTimeoutOrNull(800) { snapshotFlow { focus.targetBounds to focus.viewportBounds }.first { it.first != null && it.second != null } }
            if (bounds == null || focus.revision != token) {
                focus.cancel(id, token)
                return@LaunchedEffect
            }
            val item = checkNotNull(bounds.first)
            val viewport = checkNotNull(bounds.second)
            val delta = BrowseFocusGeometry.delta(item.top, item.bottom, viewport.top, viewport.bottom)
            if (delta == null) {
                focus.cancel(id, token)
                return@LaunchedEffect
            }
            val travel = BrowseFocusMotion.travel(delta, viewport.height)
            if (kotlin.math.abs(travel.jump) > 1) adjust(travel.jump, 0)
            if (kotlin.math.abs(travel.glide) > 1) adjust(travel.glide, travel.durationMs)
            focus.located(id, token)
        } catch (cancel: CancellationException) {
            // A user drag wins. Effect replacement may retry the same target with newer data.
            if (currentCoroutineContext().isActive) focus.cancel(id, token)
            throw cancel
        }
    }
    LaunchedEffect(focus, focus.highlightedId, token) {
        if (focus.highlightedId != null) {
            delay(2600)
            focus.clearHighlight(token)
        }
    }
}

internal fun Modifier.browseFocus(id: String): Modifier = composed {
    val focus = LocalBrowseFocus.current
    val highlighted = focus?.highlightedId == id
    val strength by animateFloatAsState(if (highlighted) 1f else 0f, tween(if (highlighted) 240 else 420), label = "Return highlight")
    val color = MaterialTheme.colorScheme.primary
    onGloballyPositioned { coordinates -> if (focus?.pendingId == id) focus.targetBounds = Rect(coordinates.positionInRoot(), androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())) }.drawWithContent {
        drawContent()
        if (strength > 0f) {
            drawRoundRect(color.copy(alpha = .09f * strength), cornerRadius = CornerRadius(4.dp.toPx()))
            drawRoundRect(color.copy(alpha = .72f * strength), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
    }
}

internal fun Modifier.browseFocusViewport(): Modifier = composed {
    val focus = LocalBrowseFocus.current
    onGloballyPositioned { coordinates -> if (focus != null) focus.viewportBounds = coordinates.boundsInRoot() }
}

@Composable
internal fun browseFocusBottom(default: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val focus = LocalBrowseFocus.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val height = focus?.viewportBounds?.height ?: 0f
    return if (focus?.centeringTail == true) maxOf(default, with(density) { height.toDp() / 2 }) else default
}
