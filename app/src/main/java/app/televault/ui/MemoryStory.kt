package app.televault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.televault.DriveViewModel
import kotlinx.coroutines.delay

/** Silent local story with explicit pause/navigation; opening an item keeps all normal actions. */
@Composable
internal fun MemoryStory(card: PhotoMemory, model: DriveViewModel, close: () -> Unit, open: (MediaEntry) -> Unit) {
    if (card.entries.isEmpty()) return
    var page by remember(card.key) { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var elapsed by remember(page) { mutableLongStateOf(0) }
    var loaded by remember(page) { mutableStateOf(false) }
    var detailReady by remember(page) { mutableStateOf(false) }
    var waitingForDetail by remember(page) { mutableLongStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val latestClose by rememberUpdatedState(close)
    val item = card.entries[page.coerceIn(card.entries.indices)]
    val epoch = model.playbackEpoch
    val receipt = remember(card) { card.receipt() }
    val watch = remember(receipt) { MemoryWatchState(card.entries.mapTo(hashSetOf()) { it.id }) }
    LaunchedEffect(receipt, item.id, loaded, lifecycleState, epoch) {
        if (loaded && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            // Merely opening/skipping a card does not mark unseen photos as watched.
            delay(700)
            if (watch.viewed(item.id)) model.memorySeen.mark(receipt, epoch)
        }
    }
    LaunchedEffect(card.key, page) {
        while (elapsed < 6000) {
            delay(50)
            if (loaded && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                waitingForDetail += 50
                if (!paused && !held && (detailReady || waitingForDetail >= 4000)) elapsed += 50
            }
        }
        if (page < card.entries.lastIndex) page++ else latestClose()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                card.entries.forEachIndexed { index, _ ->
                    LinearProgressIndicator(progress = {
                        if (index < page) {
                            1f
                        } else if (index == page) {
                            elapsed / 6000f
                        } else {
                            0f
                        }
                    }, modifier = Modifier.weight(1f).height(3.dp), color = Color.White, trackColor = Color.DarkGray)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.title ?: if (card.years > 0) if (card.years == 1) "1 year ago" else "${card.years} years ago" else "A day to remember", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("${card.date} · ${page + 1}/${card.entries.size}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                IconButton({ paused = !paused }) { Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (paused) "Play story" else "Pause story", tint = Color.White) }
                IconButton(close) { Icon(Icons.Outlined.Close, "Close story", tint = Color.White) }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(card.key, page) {
                    detectTapGestures(onPress = {
                        held = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            held = false
                        }
                    }, onTap = { point ->
                        if (point.x < size.width / 3f) {
                            if (page > 0) page-- else elapsed = 0
                        } else {
                            if (page < card.entries.lastIndex) page++ else close()
                        }
                    })
                },
                contentAlignment = Alignment.Center
            ) {
                ScrubMediaPreview(item, model, onReady = { loaded = true }, onDetailReady = { detailReady = true })
                PrepareScrubPreviews(card.entries, page, scrubbing = true, enabled = lifecycleState.isAtLeast(Lifecycle.State.RESUMED), model = model)
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (page > 0) page-- }, enabled = page > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous photo", tint = if (page > 0) Color.White else Color.Gray) }
                TextButton({
                    paused = true
                    open(item)
                }) { Text("View photo", color = Color.White) }
                IconButton({ if (page < card.entries.lastIndex) page++ else close() }) { Icon(Icons.Outlined.ChevronRight, "Next photo", tint = Color.White) }
            }
        }
    }
}
