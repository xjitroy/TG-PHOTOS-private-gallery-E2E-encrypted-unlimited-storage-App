package app.televault.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.televault.domain.DragSelectionRange
import kotlin.math.abs
import kotlinx.coroutines.isActive

/** Only visible thumbnail bounds are registered, including individual children of mosaic blocks. */
internal class GridDragSelection {
    data class Tile(val owner: Any, val bounds: Rect)
    val tiles = mutableMapOf<String, Tile>()
    var viewport = Rect.Zero
    var active by mutableStateOf(false)
    var point = Offset.Unspecified
    var range: DragSelectionRange? = null
    var last: String? = null
    fun hit(position: Offset): String? = if (!viewport.contains(position)) null else tiles.entries.firstOrNull { it.value.bounds.overlaps(viewport) && it.value.bounds.contains(position) }?.key
    fun nearest(position: Offset): String? = hit(position) ?: tiles.entries.filter { it.value.bounds.overlaps(viewport) }.minByOrNull {
        val rect = it.value.bounds
        val dy = maxOf(rect.top - position.y, position.y - rect.bottom, 0f)
        val dx = maxOf(rect.left - position.x, position.x - rect.right, 0f)
        dy * 10000f + dx
    }?.key
}

@Composable
internal fun rememberGridDragSelection(grid: LazyGridState, ids: List<String>, selected: Set<String>, change: (Set<String>) -> Unit): Pair<GridDragSelection, Modifier> {
    val drag = remember { GridDragSelection() }
    val currentIds by rememberUpdatedState(ids)
    val currentIdSet by rememberUpdatedState(remember(ids) { ids.toSet() })
    val currentSelection by rememberUpdatedState(selected)
    val currentChange by rememberUpdatedState(change)
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val speed = with(LocalDensity.current) { 1000.dp.toPx() }
    fun applyPoint() {
        val id = drag.nearest(drag.point) ?: return
        if (id == drag.last || id !in currentIdSet) return
        drag.last = id
        drag.range?.at(id)?.let { currentChange(it.intersect(currentIdSet)) }
    }
    LaunchedEffect(drag.active, grid) {
        var previous = 0L
        while (isActive && drag.active) {
            val now = withFrameNanos { it }
            val elapsed = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceAtMost(.032f)
            previous = now
            val y = drag.point.y
            val bounds = drag.viewport
            val factor = when {
                y < bounds.top + edge -> -((bounds.top + edge - y) / edge).coerceIn(0f, 1f)
                y > bounds.bottom - edge -> ((y - bounds.bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (abs(factor) > 0) {
                grid.scrollBy(factor * speed * elapsed)
                applyPoint()
            }
        }
    }
    val modifier = Modifier.onGloballyPositioned { drag.viewport = it.boundsInWindow() }.pointerInput(drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (grid.layoutInfo.visibleItemsInfo.any { item -> item.key.toString().startsWith("date:") && down.position.y >= item.offset.y && down.position.y < item.offset.y + item.size.height }) return@awaitEachGesture
            val first = drag.hit(down.position + drag.viewport.topLeft) ?: return@awaitEachGesture
            val order = currentIds.toList()
            val baseline = currentSelection.toSet()
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (first !in currentIdSet) return@awaitEachGesture
            drag.range = DragSelectionRange(order, baseline, first)
            drag.point = press.position + drag.viewport.topLeft
            drag.last = first
            drag.active = true
            currentChange(drag.range!!.at(first))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            press.consume()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) break
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!pointer.pressed) break
                    drag.point = pointer.position + drag.viewport.topLeft
                    applyPoint()
                    event.changes.forEach { it.consume() }
                }
            } finally {
                drag.active = false
                drag.range = null
                drag.last = null
            }
        }
    }
    return drag to modifier
}

@Composable
internal fun Modifier.dragSelectionTile(id: String, drag: GridDragSelection): Modifier {
    val owner = remember(id) { Any() }
    DisposableEffect(id, drag) { onDispose { if (drag.tiles[id]?.owner === owner) drag.tiles.remove(id) } }
    return onGloballyPositioned { drag.tiles[id] = GridDragSelection.Tile(owner, it.boundsInWindow()) }
}
