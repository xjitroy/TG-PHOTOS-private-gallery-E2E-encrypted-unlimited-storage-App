package app.televault.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.domain.ExploreMedia
import app.televault.domain.MediaCategories

internal val LocalExploreFeed = staticCompositionLocalOf { false }
internal val LocalExploreMode = staticCompositionLocalOf { "" }
internal data class ExploreAction(val title: String, val icon: ImageVector)

/** Transparent anchor; only the compact labels receive a translucent readability surface. */
@Composable
internal fun PhotoExploreButton(preferences: app.televault.PreferencesRepository, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val introSeen by preferences.exploreIntroSeen.collectAsStateWithLifecycle(initialValue = true)
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(introSeen) {
        if (!introSeen) {
            glow.snapTo(.75f)
            glow.animateTo(0f, tween(1800))
            preferences.markExploreIntroSeen()
        }
    }
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = expanded
    val actions = listOf(ExploreAction("Shorts", Icons.Outlined.SlowMotionVideo), ExploreAction("All videos", Icons.Outlined.VideoLibrary), ExploreAction("Normal videos", Icons.Outlined.PlayCircle), ExploreAction("Movies", Icons.Outlined.Movie), ExploreAction("Documents", Icons.Outlined.Description), ExploreAction("All people", Icons.Outlined.People), ExploreAction("Profile", Icons.Outlined.AccountCircle), ExploreAction("Upload", Icons.Outlined.Add))
    val offset = with(LocalDensity.current) { 62.dp.roundToPx() }
    val halo = MaterialTheme.colorScheme.primary
    Box(
        Modifier.drawBehind {
            if (glow.value > 0f) drawCircle(Brush.radialGradient(listOf(halo.copy(alpha = .35f * glow.value), Color.Transparent)), radius = size.maxDimension * .8f)
        }
    ) {
        AppSurface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(28.dp), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground, border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = .28f))) {
            Row(Modifier.size(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(if (expanded) Icons.Outlined.Close else Icons.Outlined.Explore, "Explore your library", Modifier.size(22.dp))
            }
        }
        if (transition.currentState || transition.targetState) {
            Popup(alignment = Alignment.BottomEnd, offset = IntOffset(0, -offset), onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                AnimatedVisibility(transition, enter = fadeIn(tween(140)) + slideInVertically(tween(160)) { it / 8 }, exit = fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 10 }) {
                    val available = (LocalConfiguration.current.screenHeightDp - 180).coerceAtLeast(160).dp
                    Column(Modifier.heightIn(max = available).verticalScroll(rememberScrollState()).padding(4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        actions.forEach { action ->
                            AppSurface(onClick = {
                                expanded = false
                                choose(action.title)
                            }, shape = RoundedCornerShape(22.dp), color = chromeColor(), contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, Color.White.copy(alpha = .18f))) {
                                Row(Modifier.heightIn(min = 46.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(if (action.title == "Upload") "Upload +" else action.title, style = MaterialTheme.typography.labelLarge)
                                    Icon(action.icon, null, Modifier.size(21.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun exploreShort(item: MediaEntry): Boolean = ExploreMedia.shortVideo(item.cloud?.facts?.width ?: item.local?.width ?: 0, item.cloud?.facts?.height ?: item.local?.height ?: 0, item.cloud?.facts?.durationMs ?: item.local?.durationMs ?: 0, item.mediaCategories and MediaCategories.Category.MOVIES.mask != 0)
private fun documentType(item: MediaEntry): String = when {
    item.mime == "application/pdf" -> "PDF"
    item.mime.startsWith("image/") -> "Scans"
    item.name.substringAfterLast('.', "").lowercase() in setOf("doc", "docx", "odt", "rtf") -> "Documents"
    item.name.substringAfterLast('.', "").lowercase() in setOf("xls", "xlsx", "ods", "csv") -> "Spreadsheets"
    item.name.substringAfterLast('.', "").lowercase() in setOf("ppt", "pptx", "odp") -> "Presentations"
    item.mime.startsWith("text/") -> "Text"
    else -> "Other"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExploreCollection(mode: String, files: List<MediaEntry>, model: DriveViewModel, close: () -> Unit, open: (MediaEntry, List<MediaEntry>) -> Unit) {
    var sort by remember(mode) { mutableStateOf("Newest") }
    var type by remember(mode) { mutableStateOf("All") }
    val projection = rememberBackgroundProjection(listOf(mode, sort, type), files, emptyList<MediaEntry>()) { all ->
        val items = all.filter { item ->
            val movie = item.mediaCategories and MediaCategories.Category.MOVIES.mask != 0
            when (mode) {
                "Movies" -> item.mime.startsWith("video/") && movie
                "Normal videos" -> item.mime.startsWith("video/") && !movie && !exploreShort(item)
                "Documents" -> item.mediaCategories and MediaCategories.Category.DOCUMENTS.mask != 0 || libraryCategory(item) == "Documents"
                else -> item.mime.startsWith("video/")
            } && (mode != "Documents" || type == "All" || documentType(item) == type)
        }
        when (sort) {
            "Oldest" -> items.sortedWith(compareBy<MediaEntry> { it.date }.thenBy { it.id })
            "Name" -> items.sortedWith(compareBy<MediaEntry> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id })
            "Largest" -> items.sortedWith(compareByDescending<MediaEntry> { it.size }.thenBy { it.id })
            else -> items.sortedWith(compareByDescending<MediaEntry> { it.date }.thenBy { it.id })
        }
    }
    val items = projection.value
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val focusPositions = remember(items) { items.mapIndexed { index, item -> item.id to index }.toMap() }
    LocateBrowseFocus(focusPositions, { scroll.firstVisibleItemIndex }, { scroll.scrollToItem(it) }, { scroll.animateScrollBy(it, tween(180)) })
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        AppSurface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopAppBar(title = { Text(mode) }, navigationIcon = { IconButton(close) { Icon(Icons.Outlined.ArrowBack, "Back") } })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Newest", "Oldest", "Name", "Largest").forEach { value -> FilterChip(sort == value, { sort = value }, label = { Text(value) }) }
                }
                if (mode == "Documents") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "PDF", "Documents", "Scans", "Spreadsheets", "Presentations", "Text", "Other").forEach { value -> FilterChip(type == value, { type = value }, label = { Text(value) }) }
                    }
                }
                Text("${items.size} items · Device & encrypted cloud", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().browseFocusViewport(), state = scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = browseFocusBottom(16.dp)), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (items.isEmpty()) item { Text(if (projection.ready) "Nothing here yet. Your library updates automatically." else "Reading your library…", Modifier.padding(16.dp)) }
                    items(items, key = { it.id }) { item ->
                        AppSurface(onClick = { open(item, items) }, modifier = Modifier.browseFocus(item.id), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            if (mode == "Documents") {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Box(Modifier.size(64.dp)) { MediaThumbnail(item, model, Modifier.fillMaxSize(), 192) }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                        Text(documentType(item) + " · " + sizeLabel(item.size), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(Icons.Outlined.ChevronRight, "Open")
                                }
                            } else {
                                Column {
                                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                        MediaThumbnail(item, model, Modifier.fillMaxSize(), 768, ContentScale.Crop)
                                        Icon(Icons.Outlined.PlayCircle, "Play video", Modifier.align(Alignment.Center).size(46.dp), tint = Color.White)
                                    }
                                    Text(item.name, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
