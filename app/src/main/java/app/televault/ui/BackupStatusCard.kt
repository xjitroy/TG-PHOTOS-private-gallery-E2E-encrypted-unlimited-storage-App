package app.televault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.TransferOverview

/** Confirmed batch counts, one current folder and an opt-in compact queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupStatusCard(model: DriveViewModel, sync: () -> Unit, close: () -> Unit, enable: () -> Unit, manage: () -> Unit) {
    val state by model.contentState.collectAsStateWithLifecycle()
    val overview by model.backupOverview.collectAsStateWithLifecycle()
    val verification by model.backupVerification.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var attention by remember { mutableStateOf(false) }
    var lastActiveId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(overview.active?.id) { overview.active?.id?.let { lastActiveId = it } }
    // Keep the same detail slot through the brief hand-off between files; no fake activity.
    val active = overview.active ?: overview.groups.firstOrNull { it.id == lastActiveId }
    val remaining = overview.groups.filter { it.id != active?.id }
    val queued = remaining.count { it.completed < it.count }
    val confirmed = state.files.count { it.trashedAt == 0L && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) }
    // Expanded queues never push the gallery/navigation off small or rotated screens.
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .48f).coerceIn(160f, 440f).dp
    if (attention) {
        ModalBottomSheet(onDismissRequest = { attention = false }) {
            Text(label("Files needing attention", "অপেক্ষায় থাকা ফাইলগুলি"), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            TransferQueue(model, attentionOnly = true, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp))
        }
    }
    AppSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(max = maxHeight), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(label("Backup status", "ব্যাকআপের অবস্থা"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(close) { Icon(Icons.Outlined.Close, label("Close status", "স্ট্যাটাস বন্ধ")) }
            }
            val automatic = state.uploadSettings.cameraBackup || state.backups.isNotEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (automatic) label("Auto backup is on", "অটো ব্যাকআপ চালু") else label("Auto backup is off", "অটো ব্যাকআপ বন্ধ"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(if (automatic) manage else enable) { Text(if (automatic) label("Settings", "সেটিংস") else label("Turn on", "চালু করুন")) }
            }
            if (automatic) Text(label("Manage folders or turn automatic backup off in Settings → Backup & Sync.", "ফোল্ডার বদলাতে বা অটো ব্যাকআপ বন্ধ করতে Settings → Backup & Sync খুলুন।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            verification?.let { work ->
                Text(label("Checking existing backups on this phone", "ফোনের ফাইল পুরোনো ব্যাকআপের সঙ্গে যাচাই হচ্ছে") + " · ${work.completed}/${work.total}", style = MaterialTheme.typography.bodySmall)
            }
            if (overview.count > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (overview.bytes > 0) label("Overall progress · by data size", "মোট অগ্রগতি · ডেটার পরিমাণে") else label("Overall progress", "মোট অগ্রগতি"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text("${overview.percent.coerceAtLeast(0)}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(progress = { overview.percent.coerceAtLeast(0) / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp))
                    Text("${overview.completed} / ${overview.count} " + label("files backed up", "ফাইল ব্যাকআপ হয়েছে"), style = MaterialTheme.typography.bodyMedium)
                    if (overview.blocked > 0) {
                        TextButton({ attention = true }) {
                            Text("${overview.blocked} " + label("files need attention · view reasons", "ফাইল অপেক্ষায় · কারণ দেখুন"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (active != null) {
                    AppSurface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        BackupFolderRow(active, details = true, modifier = Modifier.padding(12.dp))
                    }
                }
                if (remaining.isNotEmpty()) {
                    TextButton({ expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(
                            if (queued > 0) label("Pending / queued", "অপেক্ষায় / কিউতে") + " ($queued " + label("folders", "ফোল্ডার") + ")" else label("Completed folders", "সম্পন্ন ফোল্ডার") + " (${remaining.size})",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) label("Collapse folders", "ফোল্ডার সংকুচিত করুন") else label("Expand folders", "ফোল্ডার দেখুন"))
                    }
                    AnimatedVisibility(expanded) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(remaining, key = { it.id }) { group -> BackupFolderRow(group, details = false) }
                        }
                    }
                }
            } else {
                Text(label("No queued uploads", "কোনো আপলোড অপেক্ষায় নেই"), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$confirmed " + label("photos / videos in cloud", "ছবি / ভিডিও ক্লাউডে"), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(sync, enabled = !state.busy) { Text(label("Sync now", "সিঙ্ক করুন")) }
            }
        }
    }
}

@Composable
private fun BackupFolderRow(group: TransferOverview, details: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (group.active) {
                    Icons.Outlined.CloudUpload
                } else if (group.completed == group.count) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.Folder
                },
                null,
                Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(group.name, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleSmall, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(
                if (group.completed == group.count) {
                    "100%"
                } else if (group.percent >= 0) {
                    "${group.percent}%"
                } else {
                    "—"
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (group.percent >= 0) LinearProgressIndicator(progress = { group.percent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp))
        Text("${group.completed} / ${group.count} " + label("files", "ফাইল") + if (!details && group.blocked > 0) " · ${group.blocked} " + label("need attention", "সিদ্ধান্ত প্রয়োজন") else "", style = MaterialTheme.typography.labelSmall)
        if (details) {
            val (speed, seconds) = measuredRate(group)
            Text(
                when {
                    group.completed == group.count -> label("Backup complete", "ব্যাকআপ সম্পন্ন")
                    !group.active -> label("Queued / waiting for access or network", "অপেক্ষায় / অনুমতি বা নেটওয়ার্ক প্রয়োজন")
                    seconds >= 0 -> label("About ", "আনুমানিক ") + if (seconds < 60) "$seconds " + label("seconds left", "সেকেন্ড বাকি") else "${(seconds + 59) / 60} " + label("minutes left", "মিনিট বাকি")
                    else -> label("Measuring upload speed…", "আপলোডের গতি মাপা হচ্ছে…")
                },
                style = MaterialTheme.typography.labelSmall
            )
            if (speed > 0) Text(sizeLabel(speed) + "/s · " + sizeLabel(group.sent) + " / " + sizeLabel(group.bytes), style = MaterialTheme.typography.labelSmall)
        }
    }
}
