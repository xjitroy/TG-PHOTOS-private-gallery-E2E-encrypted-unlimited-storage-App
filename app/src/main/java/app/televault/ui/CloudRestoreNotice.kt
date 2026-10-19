package app.televault.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.televault.DriveViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** One small foreground hint per visit, at most three persisted presentations. Never a Popup. */
@Composable internal fun CloudRestoreNotice(model: DriveViewModel, eligible: Boolean, modifier: Modifier = Modifier) {
    val restore by model.cloudRestore.collectAsStateWithLifecycle()
    val latestRestore by rememberUpdatedState(restore)
    val latestEligible by rememberUpdatedState(eligible)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var visible by remember(model.playbackEpoch) { mutableStateOf(false) }
    LaunchedEffect(model, model.playbackEpoch, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                snapshotFlow { latestEligible && latestRestore?.firstRestore == true }.first { it }
                delay(1200)
                val restoreScope = latestRestore?.scope
                if (latestEligible && latestRestore?.firstRestore == true && model.preferences.claimRestoreHint()) {
                    if (latestEligible && latestRestore?.firstRestore == true && latestRestore?.scope == restoreScope) {
                        visible = true
                        delay(5500)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Missing preference storage must not produce an unbounded series of hints.
            } finally {
                visible = false
            }
            awaitCancellation()
        }
    }
    LaunchedEffect(eligible, restore?.firstRestore, restore?.scope) {
        if (!eligible || restore?.firstRestore != true) visible = false
    }
    AnimatedVisibility(visible && eligible, modifier.widthIn(max = 480.dp), enter = fadeIn(tween(160)), exit = fadeOut(tween(100))) {
        AppSurface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.CloudDownload, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label("Files and previews are returning in the background. Keep browsing; a large library can take 10–15 minutes or longer.", "ফাইল ও প্রিভিউ পেছনে ফিরছে। দেখা চালিয়ে যান; বড় লাইব্রেরিতে ১০–১৫ মিনিট বা বেশি লাগতে পারে।"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                IconButton({
                    visible = false
                    scope.launch { runCatching { model.preferences.dismissRestoreHints() } }
                }) { Icon(Icons.Outlined.Close, label("Do not show again", "আর দেখাবেন না"), Modifier.size(18.dp)) }
            }
        }
    }
}
