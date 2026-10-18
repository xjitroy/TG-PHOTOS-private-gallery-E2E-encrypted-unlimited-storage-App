package app.televault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.televault.DriveViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/** One strip survives page changes. Only a real strip gesture may drive the main pager. */
@Composable
internal fun ViewerFilmstrip(media: List<MediaEntry>, pager: PagerState, model: DriveViewModel, enabled: Boolean, scrubbingChanged: (Boolean) -> Unit, pick: (Int, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = pager.currentPage)
    val dragged by state.interactionSource.collectIsDraggedAsState()
    var ownsScroll by remember { mutableStateOf(false) }
    val onScrubbing by rememberUpdatedState(scrubbingChanged)
    val onPick by rememberUpdatedState(pick)
    val count = media.size
    val stride = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(dragged, state.isScrollInProgress, enabled) {
        if (enabled && (dragged || state.isScrollInProgress)) {
            ownsScroll = true
            onScrubbing(true)
        } else if (ownsScroll && (!state.isScrollInProgress || !enabled)) {
            ownsScroll = false
            onScrubbing(false)
        }
    }
    DisposableEffect(Unit) { onDispose { onScrubbing(false) } }
    LaunchedEffect(state, count) {
        snapshotFlow {
            if (ownsScroll) {
                val layout = state.layoutInfo
                ViewerFilmstripGeometry.nearest(layout.visibleItemsInfo.map { FilmstripItem(it.index, it.offset, it.size) }, layout.viewportStartOffset, layout.viewportEndOffset, count)
            } else {
                null
            }
        }.distinctUntilChanged().collect { index -> if (index != null && index != pager.currentPage) onPick(index, false) }
    }
    LaunchedEffect(pager, state, count, stride) {
        snapshotFlow {
            if (ownsScroll) null else ViewerFilmstripGeometry.target(pager.currentPage + pager.currentPageOffsetFraction, count, stride)
        }.distinctUntilChanged().collect { target ->
            if (target != null) state.requestScrollToItem(target.index, target.offset)
        }
    }
    BoxWithConstraints(modifier.height(60.dp).background(androidx.compose.ui.graphics.Color.Black)) {
        val edge = ((maxWidth - 44.dp) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = edge, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            flingBehavior = rememberSnapFlingBehavior(state, snapPosition = SnapPosition.Center),
            userScrollEnabled = enabled
        ) {
            itemsIndexed(media, key = { _, item -> item.id }) { index, item ->
                val active = index == pager.currentPage
                val shape = RoundedCornerShape(7.dp)
                Box(
                    Modifier.width(44.dp).fillParentMaxHeight().clip(shape)
                        .border(BorderStroke(if (active) 2.dp else 0.dp, if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent), shape)
                        .semantics {
                            selected = active
                            contentDescription = "${originalMediaName(item.name)}, ${index + 1} of $count"
                        }.clickable(enabled = enabled) { onPick(index, true) }
                ) {
                    MediaThumbnail(item, model, Modifier.fillMaxSize().padding(if (active) 2.dp else 0.dp), 192)
                }
            }
        }
    }
}
