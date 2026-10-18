package app.televault.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.televault.DriveViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Drag changes RAM previews only. The real pager moves once, when the gesture settles. */
@Composable
internal fun ViewerFilmstrip(media: List<MediaEntry>, pager: PagerState, model: DriveViewModel, enabled: Boolean, scrubbing: Boolean, start: () -> Unit, preview: (Int) -> Unit, finish: (Int) -> Unit, cancel: () -> Unit, pick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = pager.currentPage)
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(pager.currentPage.toFloat()) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val onStart by rememberUpdatedState(start)
    val onPreview by rememberUpdatedState(preview)
    val onFinish by rememberUpdatedState(finish)
    val onCancel by rememberUpdatedState(cancel)
    val count = media.size
    val stride = with(LocalDensity.current) { 48.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val positionNow by rememberUpdatedState(if (dragging || scrubbing) position else pager.currentPage + pager.currentPageOffsetFraction)
    val gesture = remember(count) { FilmstripGesture(count, { position = it }, { onPreview(it) }, { onStart() }, { onFinish(it) }) }
    DisposableEffect(gesture) {
        onDispose {
            settling?.cancel()
            gesture.cancel()
            onCancel()
        }
    }
    val drag = rememberDraggableState { delta -> gesture.drag(delta, stride, rtl) }
    LaunchedEffect(state, count, stride) {
        snapshotFlow { ViewerFilmstripGeometry.target(positionNow, count, stride) }.distinctUntilChanged().collectLatest { target ->
            if (target != null) state.scrollToItem(target.index, target.offset)
        }
    }
    // No background or containing Surface: gaps/edge padding reveal the actual media underneath.
    BoxWithConstraints(
        modifier.height(60.dp).draggable(
            drag,
            Orientation.Horizontal,
            enabled = enabled,
            startDragImmediately = scrubbing,
            onDragStarted = {
                settling?.cancel()
                val visiblePosition = if (scrubbing) position else pager.currentPage + pager.currentPageOffsetFraction
                dragging = true
                gesture.begin(visiblePosition)
            },
            onDragStopped = { velocity ->
                dragging = false
                settling?.cancel()
                if (!enabled) {
                    gesture.cancel()
                    onCancel()
                } else {
                    settling = scope.launch {
                        val target = ViewerFilmstripGeometry.fling(position, velocity, stride, count, rtl).roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))
                        animate(position, target.toFloat(), animationSpec = tween(160)) { value, _ -> gesture.update(value) }
                        gesture.finish(target.toFloat())
                    }
                }
            }
        )
    ) {
        val edge = ((maxWidth - 44.dp) / 2).coerceAtLeast(0.dp)
        LazyRow(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = edge, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), userScrollEnabled = false) {
            itemsIndexed(media, key = { _, item -> item.id }) { index, item ->
                val active = index == positionNow.roundToInt()
                val shape = RoundedCornerShape(7.dp)
                Box(
                    Modifier.width(44.dp).fillParentMaxHeight().clip(shape)
                        .border(BorderStroke(if (active) 2.dp else 0.dp, if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent), shape)
                        .semantics {
                            selected = active
                            contentDescription = "${originalMediaName(item.name)}, ${index + 1} of $count"
                        }.clickable(enabled = enabled) {
                            settling?.cancel()
                            dragging = false
                            pick(index)
                        }
                ) {
                    MediaThumbnail(item, model, Modifier.fillMaxSize().padding(if (active) 2.dp else 0.dp), 192)
                }
            }
        }
    }
}
