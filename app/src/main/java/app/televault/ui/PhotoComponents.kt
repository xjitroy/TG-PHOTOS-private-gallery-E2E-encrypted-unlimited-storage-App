package app.televault.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.DriveSnapshot
import app.televault.domain.MosaicLayout
import app.televault.domain.ThumbnailQuality
import app.televault.domain.TimelineZoom
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.*

internal data class AlbumTile(val id: String, val name: String, val items: List<MediaEntry>, val cloud: Boolean = false, val folder: String? = null, val album: String? = null, val section: String = "All", val collage: Boolean = false)

@Composable
internal fun MediaThumbnail(entry: MediaEntry, model: DriveViewModel, modifier: Modifier = Modifier, pixels: Int = 384, contentScale: ContentScale = ContentScale.Crop, onReady: (() -> Unit)? = null, adaptive: Boolean = false) {
    var tile by remember { mutableStateOf(IntSize.Zero) }
    val measuredPhoto = adaptive && entry.mime.startsWith("image/")
    val requested = if (measuredPhoto) ThumbnailQuality.tilePixels(tile.width, tile.height, pixels) else pixels
    val accessRevision by model.localMedia.accessRevision.collectAsStateWithLifecycle()
    var bitmap by remember(entry.id, entry.local?.modified) { mutableStateOf(entry.local?.let { model.localMedia.cachedThumbnail(it, requested) } ?: model.cachedThumbnail(entry.id, requested)) }
    var completedRequest by remember(entry.id, entry.local?.modified) { mutableIntStateOf(0) }
    val ready by rememberUpdatedState(onReady)
    LaunchedEffect(bitmap) { if (bitmap != null) ready?.invoke() }
    LaunchedEffect(entry.id, entry.local?.modified, entry.cloud?.thumbnail, requested, if (entry.local != null) accessRevision else 0L) {
        repeat(3) { attempt ->
            val loaded = try {
                app.televault.data.PreviewWorkGate.thumbnails.run {
                    if (entry.local != null) model.localMedia.thumbnail(entry.local, requested) else model.thumbnail(entry.id, requested)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                // Keep cached pixels; do not repeat a failed allocation three times.
                return@LaunchedEffect
            }
            if (loaded != null) {
                val previous = bitmap
                if (previous == null || ThumbnailQuality.preferNew(previous.width, previous.height, loaded.width, loaded.height)) bitmap = loaded
                completedRequest = requested
                return@LaunchedEffect
            }
            if (attempt < 2) delay(400L * (attempt + 1))
        }
        completedRequest = requested
    }
    Box(modifier.then(if (measuredPhoto) Modifier.onSizeChanged { tile = it } else Modifier).background(if (contentScale == ContentScale.Fit) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), entry.name, Modifier.fillMaxSize(), contentScale = contentScale) } ?: Icon(
            if (entry.mime.startsWith("video/")) {
                Icons.Outlined.PlayCircle
            } else if (entry.mime.startsWith("audio/")) {
                Icons.Outlined.AudioFile
            } else if (entry.mime.startsWith("image/")) {
                Icons.Outlined.Image
            } else {
                Icons.Outlined.Description
            },
            entry.name,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (adaptive && requested >= 640 && tile.width > 0 && tile.height > 0 && entry.mime.startsWith("image/")) {
            PhotoTileDetail(entry, model, tile, completedRequest == requested && ThumbnailQuality.needsDetail(tile.width, tile.height, bitmap?.width ?: 0, bitmap?.height ?: 0))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumsGrid(device: List<AlbumTile>, cloud: List<AlbumTile>, model: DriveViewModel, open: (AlbumTile) -> Unit, create: () -> Unit, modifier: Modifier = Modifier, scopeKey: String = "albums", grid: LazyGridState = remember(scopeKey) { LazyGridState() }, leadingContent: (@Composable () -> Unit)? = null, peopleContent: (@Composable () -> Unit)? = null, deviceOnly: Boolean = false) {
    var touched by remember(grid) { mutableStateOf(false) }
    val input = remember(grid) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y != 0f) touched = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    // Async device albums prepend before cloud albums. Lazy key anchoring must not
    // keep the initial cloud-title at the top before the user has actually scrolled.
    LaunchedEffect(grid, device.firstOrNull()?.id, cloud.firstOrNull()?.id) {
        if (!touched && !grid.canScrollBackward) grid.requestScrollToItem(0)
    }
    val primary = remember(device, deviceOnly) { if (deviceOnly) device.filter { primaryDeviceAlbum(it.id, it.folder, it.section, it.collage) } else device }
    val more = remember(device, primary) { rankMoreAlbums(device.filter { it !in primary }) }
    val preview = remember(more) { moreAlbumPreview(more) }
    var showAll by androidx.compose.runtime.saveable.rememberSaveable(scopeKey) { mutableStateOf(false) }
    var albumSearch by androidx.compose.runtime.saveable.rememberSaveable(scopeKey) { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val columns = if (configuration.screenWidthDp >= 600) GridCells.Adaptive(144.dp) else GridCells.Fixed(if (deviceOnly && configuration.screenWidthDp >= 360 && configuration.fontScale < 1.35f) 3 else 2)
    LazyVerticalGrid(columns = columns, state = grid, modifier = modifier.then(Modifier.nestedScroll(input)), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (leadingContent != null) item(key = "browse-introduction", span = { GridItemSpan(maxLineSpan) }) { leadingContent() }
        if (device.isNotEmpty()) {
            if (!deviceOnly) item(key = "device-title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(label("On this device", "এই ডিভাইসে"), label("View all", "সব দেখুন")) { device.firstOrNull { it.section == "All" && it.collage }?.let(open) } }
            items(primary, key = { it.id }) { tile -> AlbumCover(tile, model, Modifier.animateItem(placementSpec = null), square = deviceOnly) { open(tile) } }
            if (more.isNotEmpty()) {
                item(key = "more-albums-title", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label("More albums", "আরও অ্যালবাম"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (more.size > MORE_ALBUM_PREVIEW_LIMIT) {
                            TextButton({
                                albumSearch = ""
                                showAll = true
                            }) {
                                Text(label("View all", "সব দেখুন"))
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
                items(preview, key = { it.id }) { tile -> AlbumCover(tile, model, Modifier.animateItem(placementSpec = null), square = true) { open(tile) } }
            }
        }
        if (!deviceOnly) {
            item(key = "cloud-title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(label("Cloud albums", "ক্লাউড অ্যালবাম"), label("Create", "তৈরি"), create) }
            if (cloud.isEmpty()) {
                item(key = "empty-cloud", span = { GridItemSpan(maxLineSpan) }) {
                    AppSurface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CloudQueue, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label("A home for backed-up memories", "ব্যাকআপ হওয়া স্মৃতির অ্যালবাম"), style = MaterialTheme.typography.titleSmall)
                                Text(label("Create albums from cloud photos", "ক্লাউডের ছবি দিয়ে অ্যালবাম তৈরি করুন"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                items(cloud, key = { "remote:${it.id}" }) { tile -> AlbumCover(tile, model, Modifier.animateItem(placementSpec = null)) { open(tile) } }
            }
        }
        if (peopleContent != null) {
            item(key = "people", span = { GridItemSpan(maxLineSpan) }) {
                // A lazy-grid item overlays multiple emitted roots. Own one vertical layout
                // so People and Recently deleted receive separate measured positions.
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) { peopleContent() }
            }
        }
        item(key = "bottom-gap", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(140.dp)) }
    }
    if (showAll) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val close = {
            scope.launch {
                sheet.hide()
                showAll = false
            }
            Unit
        }
        val matches = remember(more, albumSearch) { more.filter { albumSearch.isBlank() || it.name.contains(albumSearch.trim(), true) || it.folder.orEmpty().contains(albumSearch.trim(), true) } }
        ModalBottomSheet(onDismissRequest = { showAll = false }, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label("All folders", "সব ফোল্ডার"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(close) { Icon(Icons.Outlined.Close, label("Close", "বন্ধ করুন")) }
                }
                OutlinedTextField(albumSearch, { albumSearch = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true, placeholder = { Text(label("Search folders", "ফোল্ডার খুঁজুন")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (albumSearch.isNotEmpty()) IconButton({ albumSearch = "" }) { Icon(Icons.Outlined.Close, label("Clear search", "সার্চ মুছুন")) } })
                Text(label("${matches.size} folders · most photos and videos first", "${matches.size}টি ফোল্ডার · বেশি ছবি ও ভিডিও আগে"), Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (matches.isEmpty()) Text(label("No matching folders", "মিলছে এমন ফোল্ডার নেই"), Modifier.padding(20.dp))
                LazyVerticalGrid(columns = columns, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(matches, key = { it.id }) { tile ->
                        AlbumCover(tile, model, Modifier, square = true) {
                            showAll = false
                            open(tile)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCover(tile: AlbumTile, model: DriveViewModel, modifier: Modifier, square: Boolean = false, click: () -> Unit) {
    Column(modifier.clickable(onClick = click)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (square) 1f else 1.3f).clip(RoundedCornerShape(if (square) 22.dp else 18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (tile.collage && tile.items.isNotEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) { row ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            repeat(2) { column ->
                                val entry = tile.items.getOrNull(row * 2 + column)
                                if (entry != null) MediaThumbnail(entry, model, Modifier.weight(1f).fillMaxHeight(), 192) else Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerHighest))
                            }
                        }
                    }
                }
            } else {
                tile.items.firstOrNull()?.let { MediaThumbnail(it, model, Modifier.fillMaxSize(), adaptive = true) } ?: Icon(
                    when (tile.section) {
                        "Favorites" -> Icons.Outlined.FavoriteBorder
                        "Videos" -> Icons.Outlined.PlayCircle
                        else -> Icons.Outlined.PhotoAlbum
                    },
                    null,
                    Modifier.size(36.dp).align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (tile.cloud) {
                AppSurface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                    Row(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CloudDone, label("Cloud", "ক্লাউড"), Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(3.dp))
                        Text(label("Cloud", "ক্লাউড"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(tile.name, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${tile.items.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun SectionTitle(title: String, action: String = "", click: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        if (action.isNotBlank()) TextButton(click) { Text(action) }
    }
}

/** Only two-finger transforms are consumed; one-finger list scroll/click remains with the child. */
@Composable
internal fun Modifier.timelinePinch(density: Float, changed: (Float) -> Unit, enabled: Boolean = true): Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val latestDensity by rememberUpdatedState(density)
    val latestChanged by rememberUpdatedState(changed)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val pinch = app.televault.domain.TimelinePinch(latestDensity)
            do {
                val event = awaitPointerEvent()
                if (currentEnabled && event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f && zoom.isFinite()) {
                        val columns = pinch.update(zoom)
                        if (columns != 0) latestChanged(columns.toFloat())
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
internal fun TimelineControls(density: Float, changed: (Float) -> Unit, count: Int, mosaic: Boolean = false, changeMosaic: ((Boolean) -> Unit)? = null, compact: Boolean = false) {
    var periodMenu by remember { mutableStateOf(false) }
    val periods = listOf(TimelineZoom.DAY_COLUMNS, TimelineZoom.WEEK_COLUMNS, TimelineZoom.MONTH_COLUMNS)
    val titles = listOf(label("Day", "দিন"), label("Week", "সপ্তাহ"), label("Month", "মাস"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$count " + label("items", "আইটেম"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (compact) {
                Box {
                    TextButton({ periodMenu = true }) {
                        Text(titles[periods.indexOfFirst { TimelineZoom.period(it.toFloat()) == TimelineZoom.period(density) }.coerceAtLeast(0)])
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                    }
                    DropdownMenu(periodMenu, { periodMenu = false }) {
                        periods.forEachIndexed { index, columns ->
                            DropdownMenuItem(text = { Text(titles[index]) }, onClick = {
                                changed(columns.toFloat())
                                periodMenu = false
                            })
                        }
                    }
                }
            }
            if (changeMosaic != null) IconToggleButton(mosaic, changeMosaic) { Icon(if (mosaic) Icons.Outlined.Dashboard else Icons.Outlined.GridView, label("Mosaic photo layout", "মোজাইক ছবির বিন্যাস")) }
            IconButton({ changed((density - 1f).coerceAtLeast(2f)) }) { Icon(Icons.Outlined.ZoomIn, label("Zoom in timeline", "টাইমলাইনে জুম ইন")) }
            IconButton({ changed((density + 1f).coerceAtMost(TimelineZoom.MONTH_COLUMNS.toFloat())) }) { Icon(Icons.Outlined.ZoomOut, label("Zoom out timeline", "টাইমলাইনে জুম আউট")) }
        }
        if (!compact) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                periods.forEachIndexed { index, columns ->
                    SegmentedButton(selected = TimelineZoom.period(density) == TimelineZoom.period(columns.toFloat()), onClick = { changed(columns.toFloat()) }, shape = SegmentedButtonDefaults.itemShape(index, periods.size)) {
                        Text(
                            when (index) {
                                0 -> label("Day", "দিন")
                                1 -> label("Week", "সপ্তাহ")
                                else -> label("Month", "মাস")
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

internal fun timeGroups(files: List<MediaEntry>, density: Float): Map<String, List<MediaEntry>> {
    val period = TimelineZoom.period(density)
    val zone = java.time.ZoneId.systemDefault()
    val pattern = when (period) {
        TimelineZoom.Period.DAY -> "EEEE, d MMMM yyyy"
        TimelineZoom.Period.WEEK -> "d MMM"
        TimelineZoom.Period.MONTH -> "MMMM yyyy"
    }
    val format = java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    return files.sortedWith(compareByDescending<MediaEntry> { it.date }.thenBy { it.id }).groupBy { TimelineZoom.periodStart(it.date, density, zone) }.mapKeys { (date, _) ->
        if (period == TimelineZoom.Period.WEEK) format.format(date) + " – " + java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(date.plusDays(6)) else format.format(date)
    }
}

@Composable
internal fun MediaGrid(files: List<MediaEntry>, selected: Set<String>, model: DriveViewModel, device: Boolean, click: (MediaEntry) -> Unit, hold: (MediaEntry) -> Unit, density: Float, densityChanged: (Float) -> Unit, grid: LazyGridState = rememberLazyGridState(), mosaic: Boolean = true, selectionChanged: (Set<String>) -> Unit = {}, leadingContent: (@Composable () -> Unit)? = null, showDateHeaders: Boolean = true, onFastScroll: () -> Unit = {}, fastScrollTop: androidx.compose.ui.unit.Dp = 0.dp) {
    val dateAlpha by animateFloatAsState(if (showDateHeaders) 1f else 0f, tween(180), label = "Timeline dates")
    val snapshot by model.contentState.collectAsStateWithLifecycle()
    var anchor by remember { mutableStateOf<String?>(null) }
    val columns = TimelineZoom.columns(density)
    val layoutScope = listOf(model.playbackEpoch, LocalBrowseFocus.current ?: grid, TimelineZoom.period(density), java.time.ZoneId.systemDefault(), Locale.getDefault())
    val groups = rememberBackgroundProjection(layoutScope, files, emptyMap<String, List<MediaEntry>>()) { timeGroups(it, density) }.value
    val dragIds = remember(groups) { groups.values.flatten().map { it.id } }
    val (drag, dragModifier) = rememberGridDragSelection(grid, dragIds, selected, selectionChanged)
    val guardedHold: (MediaEntry) -> Unit = { if (!drag.active) hold(it) }
    val useMosaic = mosaic && columns in 3..4
    val mosaicPlans = remember(grid, TimelineZoom.period(density), useMosaic) { mutableMapOf<String, app.televault.domain.StableMosaicPlan.Snapshot>() }
    val blocks = remember(groups, useMosaic) {
        mosaicPlans.keys.retainAll(groups.keys)
        if (useMosaic) {
            groups.mapValues { (date, entries) ->
                app.televault.domain.StableMosaicPlan.update(mosaicPlans[date], entries.map { it.id }) { entries[it].mime.startsWith("image/") }.also { mosaicPlans[date] = it }.blocks()
            }
        } else {
            emptyMap()
        }
    }
    val keyPositions = remember(groups, blocks, leadingContent != null) {
        buildMap<String, Int> {
            var position = 0
            if (leadingContent != null) put("timeline-introduction", position++)
            groups.forEach { (date, entries) ->
                put("date:$date", position++)
                if (useMosaic) {
                    blocks.getValue(date).forEach { block ->
                        block.cells().forEach { put(entries[it.index()].id, position) }
                        position++
                    }
                } else {
                    entries.forEach { put(it.id, position++) }
                }
            }
        }
    }
    LocateBrowseFocus(keyPositions, { grid.firstVisibleItemIndex }, { grid.scrollToItem(it) }, { distance, duration -> if (duration == 0) grid.scrollBy(distance) else grid.animateScrollBy(distance, tween(duration, easing = androidx.compose.animation.core.FastOutSlowInEasing)) })
    val focusPending = LocalBrowseFocus.current?.pendingId
    val geometry = remember(grid) { intArrayOf(columns, if (useMosaic) 1 else 0) }
    var touched by remember(grid) { mutableStateOf(false) }
    val input = remember(grid) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y != 0f) touched = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val browseFocus = LocalBrowseFocus.current
    LaunchedEffect(browseFocus?.highlightedId) { if (browseFocus?.highlightedId != null) touched = true }
    LaunchedEffect(grid, groups.values.firstOrNull()?.firstOrNull()?.id) {
        if (!touched && focusPending == null && !grid.canScrollBackward) grid.requestScrollToItem(0)
    }
    LaunchedEffect(grid, columns, useMosaic) {
        val next = if (useMosaic) 1 else 0
        // The old first-composition anchor scrolled to item1 under the sticky date
        // header, cutting the first row. Anchor only after a real geometry change.
        if (geometry[0] == columns && geometry[1] == next) return@LaunchedEffect
        geometry[0] = columns
        geometry[1] = next
        val first = anchor ?: grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key is String && !(it.key as String).startsWith("date:") }?.key as? String
        val position = first?.let { keyPositions[it] }
        val heading = grid.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("date:") == true }?.size?.height ?: 0
        if (position != null) grid.scrollToItem(position, -heading)
        anchor = null
    }
    val change: (Float) -> Unit = { value ->
        anchor = grid.layoutInfo.visibleItemsInfo.firstOrNull { item -> item.key is String && !(item.key as String).startsWith("date:") }?.key as? String
        densityChanged(value)
    }
    val fastScrollMap = remember(groups, blocks, leadingContent != null) {
        var index = if (leadingContent != null) 1 else 0
        val dates = groups.map { (date, entries) ->
            TimelineScrollSection(index, date).also { index += 1 + if (useMosaic) blocks.getValue(date).size else entries.size }
        }
        TimelineFastScrollMap(index, dates)
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize().browseFocusViewport().nestedScroll(input).then(dragModifier).timelinePinch(density, change, enabled = !drag.active), contentPadding = PaddingValues(bottom = browseFocusBottom(92.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (leadingContent != null) item(key = "timeline-introduction", span = { GridItemSpan(maxLineSpan) }, contentType = "timeline-introduction") { leadingContent() }
            groups.forEach { (date, group) ->
                stickyHeader(key = "date:$date") {
                    // Fade paint only: changing a sticky item's height moves every media row.
                    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = dateAlpha }.padding(top = if (leadingContent != null) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() else 0.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                        AppSurface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .94f), shadowElevation = 3.dp) {
                            Text(date, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (useMosaic) {
                    items(blocks.getValue(date), key = { group[it.cells().first().index()].id }, span = { GridItemSpan(maxLineSpan) }) { block ->
                        MosaicPhotoBlock(group, block, selected, snapshot, model, click, guardedHold, drag, Modifier)
                    }
                } else {
                    items(group, key = { it.id }) { entry ->
                        MediaGridCell(
                            entry,
                            selected,
                            snapshot,
                            model,
                            ThumbnailQuality.gridPixels(columns, false),
                            click,
                            guardedHold,
                            Modifier.aspectRatio(1f).animateItem(placementSpec = null).dragSelectionTile(entry.id, drag)
                        )
                    }
                }
            }
        }
        val fastScrollAvailable by remember(grid, leadingContent != null) {
            derivedStateOf { (leadingContent == null || grid.firstVisibleItemIndex > 0) && (grid.canScrollForward || grid.canScrollBackward) }
        }
        if (files.size >= 100 && selected.isEmpty() && fastScrollAvailable) {
            TimelineFastScroll(grid, fastScrollMap, onUserScroll = onFastScroll, topObstruction = fastScrollTop)
        }
    }
}

/** Measure only visible lazy blocks. Uniform virtual cells keep photo order and never overlap. */
@Composable
private fun MosaicPhotoBlock(files: List<MediaEntry>, block: MosaicLayout.Block, selected: Set<String>, snapshot: DriveSnapshot, model: DriveViewModel, click: (MediaEntry) -> Unit, hold: (MediaEntry) -> Unit, drag: GridDragSelection, modifier: Modifier) {
    Layout(content = {
        block.cells().forEach { cell ->
            val entry = files[cell.index()]
            key(entry.id) {
                MediaGridCell(
                    entry,
                    selected,
                    snapshot,
                    model,
                    ThumbnailQuality.gridPixels(4, cell.columns() > 1),
                    click,
                    hold,
                    Modifier.dragSelectionTile(entry.id, drag)
                )
            }
        }
    }, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 2.dp.roundToPx()
        val unit = (constraints.maxWidth - gap * 3).coerceAtLeast(4) / 4f
        fun edge(value: Int) = (value * (unit + gap)).roundToInt()
        val tiles = measurables.mapIndexed { index, measurable ->
            val cell = block.cells()[index]
            val width = (edge(cell.column() + cell.columns()) - gap - edge(cell.column())).coerceAtLeast(1)
            val height = (edge(cell.row() + cell.rows()) - gap - edge(cell.row())).coerceAtLeast(1)
            measurable.measure(Constraints.fixed(width, height))
        }
        layout(constraints.maxWidth, edge(block.rows()) - gap) {
            tiles.forEachIndexed { index, tile ->
                val cell = block.cells()[index]
                tile.placeRelative(edge(cell.column()), edge(cell.row()))
            }
        }
    }
}

@Composable
private fun MediaGridCell(entry: MediaEntry, selected: Set<String>, snapshot: DriveSnapshot, model: DriveViewModel, pixels: Int, click: (MediaEntry) -> Unit, hold: (MediaEntry) -> Unit, modifier: Modifier) {
    val chosen = entry.id in selected
    val scale by animateFloatAsState(if (chosen) .89f else 1f, tween(170), label = "Selection")
    Box(modifier.clip(RoundedCornerShape(if (chosen) 16.dp else 4.dp)).browseFocus(entry.id).background(MaterialTheme.colorScheme.surfaceContainerHighest).combinedClickable(onClick = { click(entry) }, onLongClick = { hold(entry) })) {
        MediaThumbnail(
            entry,
            model,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            pixels,
            adaptive = true
        )
        if (!entry.mime.startsWith("image/") && !entry.mime.startsWith("video/")) {
            Text(entry.name, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(5.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
        if (entry.mime.startsWith("video/")) Icon(Icons.Outlined.PlayCircle, null, Modifier.align(Alignment.BottomEnd).padding(7.dp), tint = Color.White)
        if (recentBackupBadge(entry, model)) BadgeIcon(Icons.Outlined.CloudDone, label("Backed up", "ব্যাকআপ হয়েছে"), Modifier.align(Alignment.BottomStart))
        if (entry.cloud?.favorite == true || entry.id in snapshot.localFavorites) BadgeIcon(Icons.Outlined.Favorite, label("Favorite", "প্রিয়"), Modifier.align(Alignment.TopStart))
        if (chosen) BadgeIcon(Icons.Outlined.CheckCircle, label("Selected", "নির্বাচিত"), Modifier.align(Alignment.TopEnd))
    }
}

@Composable private fun BadgeIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, modifier: Modifier) {
    AppSurface(modifier.padding(5.dp), color = Color.Black.copy(alpha = .52f), shape = RoundedCornerShape(14.dp)) { Icon(icon, name, Modifier.padding(4.dp).size(16.dp), tint = Color.White) }
}

@Composable
internal fun DocumentTimeline(files: List<MediaEntry>, selected: Set<String>, model: DriveViewModel, density: Float, changed: (Float) -> Unit, open: (MediaEntry) -> Unit, hold: (MediaEntry) -> Unit, scroll: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), leadingContent: (@Composable () -> Unit)? = null, showDateHeaders: Boolean = true) {
    val dateAlpha by animateFloatAsState(if (showDateHeaders) 1f else 0f, tween(180), label = "Document dates")
    val layoutScope = listOf(model.playbackEpoch, LocalBrowseFocus.current ?: scroll, TimelineZoom.period(density), java.time.ZoneId.systemDefault(), Locale.getDefault())
    val groups = rememberBackgroundProjection(layoutScope, files, emptyMap<String, List<MediaEntry>>()) { timeGroups(it, density) }.value
    val positions = remember(groups, leadingContent != null) {
        buildMap<String, Int> {
            var index = if (leadingContent != null) 1 else 0
            groups.values.forEach { entries ->
                index++
                entries.forEach { put(it.id, index++) }
            }
        }
    }
    LocateBrowseFocus(positions, { scroll.firstVisibleItemIndex }, { scroll.scrollToItem(it) }, { distance, duration -> if (duration == 0) scroll.scrollBy(distance) else scroll.animateScrollBy(distance, tween(duration, easing = androidx.compose.animation.core.FastOutSlowInEasing)) })
    LazyColumn(Modifier.fillMaxSize().browseFocusViewport().timelinePinch(density, changed), state = scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = browseFocusBottom(110.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leadingContent != null) item(key = "browse-introduction") { leadingContent() }
        groups.forEach { (date, entries) ->
            stickyHeader(key = "date:$date") {
                Text(date, Modifier.fillMaxWidth().graphicsLayer { alpha = dateAlpha }.background(MaterialTheme.colorScheme.background).padding(top = if (leadingContent != null) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() else 0.dp).padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(entries, key = { it.id }) { entry ->
                AppSurface(shape = RoundedCornerShape(22.dp), color = if (entry.id in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.animateItem(placementSpec = null).browseFocus(entry.id).combinedClickable(onClick = { open(entry) }, onLongClick = { hold(entry) })) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppSurface(shape = RoundedCornerShape(16.dp), color = mediaAccentContainer(entry.mime)) { Icon(if (entry.mime.startsWith("audio/")) Icons.Outlined.AudioFile else Icons.Outlined.Description, null, Modifier.padding(14.dp).size(24.dp), tint = mediaAccent(entry.mime)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(sizeLabel(entry.size) + " · " + entry.mime.substringAfter('/').uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (recentBackupBadge(entry, model)) Icon(Icons.Outlined.CloudDone, label("Backed up", "ব্যাকআপ হয়েছে"), Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.primary)
                        Icon(if (entry.id in selected) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

internal fun originalMediaName(name: String): String = name.replace(Regex("^\\.trashed-\\d+-"), "")

@Composable
internal fun DeviceLibraryFolders(files: List<MediaEntry>, scroll: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), leadingContent: (@Composable () -> Unit)? = null, open: (String) -> Unit) {
    val folders = remember(files) { files.groupBy { it.local!!.folder }.entries.sortedWith(compareByDescending<Map.Entry<String, List<MediaEntry>>> { entry -> entry.value.maxOf { app.televault.domain.LocalFolderOrder.updatedAt(it.date, it.local!!.modified) } }.thenBy { it.key.lowercase() }) }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (leadingContent != null) item(key = "browse-introduction") { leadingContent() }
        items(folders, key = { it.key }) { (path, entries) ->
            val colors = folderColors(path)
            AppSurface(Modifier.animateItem(placementSpec = null).clickable { open(path) }, shape = RoundedCornerShape(24.dp), color = colors.container) {
                ListItem(headlineContent = { Text(path.substringAfterLast('/').ifBlank { path }) }, supportingContent = { Text("$path · ${entries.size} " + label("files", "ফাইল")) }, leadingContent = { Icon(Icons.Outlined.Folder, null, tint = colors.accent) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, label("Open folder", "ফোল্ডার খুলুন")) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
}

@Composable
private fun recentBackupBadge(entry: MediaEntry, model: DriveViewModel): Boolean {
    val times by model.backupBadgeTimes.collectAsStateWithLifecycle()
    val timestamp = times[entry.cloud?.id ?: entry.local?.fingerprint] ?: 0L
    val visible by produceState(app.televault.domain.BackupBadge.visible(timestamp, System.currentTimeMillis()), timestamp) {
        value = app.televault.domain.BackupBadge.visible(timestamp, System.currentTimeMillis())
        if (value) {
            kotlinx.coroutines.delay((timestamp + app.televault.domain.BackupBadge.DURATION - System.currentTimeMillis()).coerceAtLeast(1))
            value = false
        }
    }
    return visible
}
