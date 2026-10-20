package app.televault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

internal data class TimelineScrollSection(val firstItem: Int, val date: String)

/** Maps lazy item indices, including date rows and mosaic blocks, rather than photo counts. */
internal class TimelineFastScrollMap(val totalItems: Int, private val sections: List<TimelineScrollSection>) {
    fun target(fraction: Float): Int = if (!fraction.isFinite() || totalItems <= 1) 0 else ((totalItems - 1).toDouble() * fraction.coerceIn(0f, 1f)).roundToInt()
    fun fraction(index: Int): Float = if (totalItems <= 1) 0f else index.coerceIn(0, totalItems - 1).toFloat() / (totalItems - 1)
    fun date(index: Int): String {
        if (sections.isEmpty()) return ""
        var low = 0
        var high = sections.lastIndex
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (sections[middle].firstItem <= index) low = middle else high = middle - 1
        }
        return sections[low].date
    }
}

/** A visible edge thumb owns only its48dp touch target; the photos keep their gestures. */
@Composable
internal fun TimelineFastScroll(grid: LazyGridState, map: TimelineFastScrollMap, modifier: Modifier = Modifier, onUserScroll: () -> Unit = {}, topObstruction: Dp = 0.dp) {
    var dragging by remember(grid) { mutableStateOf(false) }
    var dragFraction by remember(grid) { mutableFloatStateOf(0f) }
    val requests = remember(grid) { Channel<Int>(Channel.CONFLATED) }
    val userScroll by rememberUpdatedState(onUserScroll)
    DisposableEffect(requests) { onDispose { requests.close() } }
    LaunchedEffect(grid, requests) {
        requests.receiveAsFlow().collectLatest { grid.scrollToItem(it) }
    }
    val current by remember(grid, map) {
        derivedStateOf {
            if (!grid.canScrollForward && grid.canScrollBackward) 1f else map.fraction(grid.firstVisibleItemIndex)
        }
    }
    val fraction = if (dragging) dragFraction else current
    val latestFraction by rememberUpdatedState(current)
    val emphasis by animateFloatAsState(if (dragging || grid.isScrollInProgress) 1f else .64f, tween(160), label = "Fast scroll handle")
    val description = label("Scroll library", "লাইব্রেরি স্ক্রল করুন")
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val topInset = maxOf(safe.calculateTopPadding(), topObstruction) + 8.dp
    BoxWithConstraints(modifier.fillMaxSize().padding(top = topInset, end = safe.calculateEndPadding(LocalLayoutDirection.current), bottom = browseFocusBottom(100.dp))) {
        val travel = with(LocalDensity.current) { (maxHeight - 48.dp).coerceAtLeast(1.dp).toPx() }
        val top = (travel * fraction).roundToInt()
        val latestTravel by rememberUpdatedState(travel)
        val latestMap by rememberUpdatedState(map)
        if (dragging) {
            AppSurface(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, top) }.padding(end = 52.dp).widthIn(max = maxWidth - 56.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(map.date(map.target(fraction)), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, top) }.size(48.dp).semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value ->
                    userScroll()
                    requests.trySend(map.target(value))
                    true
                }
            }.pointerInput(grid) {
                detectVerticalDragGestures(
                    onDragStart = {
                        userScroll()
                        dragFraction = latestFraction
                        dragging = true
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                ) { change, amount ->
                    change.consume()
                    dragFraction = (dragFraction + amount / latestTravel).coerceIn(0f, 1f)
                    requests.trySend(latestMap.target(dragFraction))
                }
            },
            contentAlignment = Alignment.CenterEnd
        ) {
            AppSurface(Modifier.padding(end = 4.dp).width(24.dp).height(40.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = emphasis)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
    }
}
