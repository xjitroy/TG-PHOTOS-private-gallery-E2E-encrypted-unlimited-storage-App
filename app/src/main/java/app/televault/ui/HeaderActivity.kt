package app.televault.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.TransferOverview
import app.televault.domain.ActivityProgress
import app.televault.domain.TransferRate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Fixed subtitle slot: progress can change without measuring/moving the content below it. */
@Composable internal fun HeaderActivity(model: DriveViewModel) {
    val offline by model.offlineDownloads.collectAsStateWithLifecycle()
    val offlineWork = offline.activeId?.let { app.televault.data.WorkProgress(label("Encrypted offline download", "এনক্রিপ্টেড অফলাইন ডাউনলোড"), offline.progress, offline.total) }
    val groups by model.transferOverview.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val exports by model.cloudExports.history.collectAsStateWithLifecycle()
    val exportWork = exports.firstOrNull { it.phase in setOf("PREPARING", "EXPORTING", "VERIFYING") }?.let {
        app.televault.data.WorkProgress(if (it.phase == "VERIFYING") label("Verifying Cloud ZIP", "Cloud ZIP যাচাই হচ্ছে") else label("Cloud ZIP export", "Cloud ZIP এক্সপোর্ট"), it.bytes, it.total)
    }
    val fileWork by model.deviceFileTrash.activity.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val privateVault = (context.applicationContext as app.televault.TeleVaultApplication).privateVault
    val vaultProgress by privateVault.progress.collectAsStateWithLifecycle()
    val vaultOperation by privateVault.operation.collectAsStateWithLifecycle()
    val privateUnlocked by privateVault.unlocked.collectAsStateWithLifecycle()
    val privateWork = vaultProgress.takeIf { privateUnlocked }
    val group = groups.firstOrNull { it.active }
    val work = offlineWork ?: fileWork ?: state.deviceCopyProgress ?: state.syncProgress ?: exportWork
    var retained by remember { mutableStateOf<TransferOverview?>(null) }
    LaunchedEffect(group) {
        if (group != null) {
            retained = group
        } else {
            delay(1800)
            retained = null
        }
    }
    val displayed = group ?: retained?.let { previous -> groups.firstOrNull { it.id == previous.id } }
    val (_, seconds) = displayed?.let { measuredRate(it) } ?: (0L to -1L)
    val eta = if (seconds < 0) {
        ""
    } else if (seconds < 60) {
        " · ~${seconds}s"
    } else {
        " · ~${(seconds + 59) / 60}m"
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 32.dp), contentAlignment = Alignment.CenterStart) {
        if (privateWork != null || displayed != null || work != null) {
            val percent = privateWork?.let { ActivityProgress.percent(it.first, it.second) } ?: displayed?.percent ?: work?.let { ActivityProgress.percent(it.completed, it.total) } ?: -1
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SecureLoader(Modifier.size(20.dp))
                if (percent >= 0) Text("$percent%", style = MaterialTheme.typography.labelSmall)
                Text(
                    if (privateWork != null) {
                        when (vaultOperation) {
                            "encrypt-upload" -> label("Vault · encrypting files", "Vault · ফাইল এনক্রিপ্ট হচ্ছে")
                            "verify-upload" -> label("Vault · verifying server copy", "Vault · সার্ভারের কপি যাচাই")
                            "commit-upload" -> label("Vault · saving verified entry", "Vault · যাচাই করা এন্ট্রি সংরক্ষণ")
                            "zip" -> label("Vault · encrypted ZIP", "Vault · এনক্রিপ্টেড ZIP")
                            "verify-zip" -> label("Vault · verifying ZIP", "Vault · ZIP যাচাই")
                            else -> label("Vault · encrypted upload", "Vault · এনক্রিপ্টেড আপলোড")
                        }
                    } else if (displayed != null) {
                        "${displayed.completed}/${displayed.count}$eta · ${displayed.name}"
                    } else {
                        work?.name ?: label("Cloud / file operation", "ক্লাউড / ফাইলের কাজ")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.CloudQueue, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label("Private & encrypted", "প্রাইভেট ও এনক্রিপ্টেড"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable internal fun measuredRate(group: TransferOverview): Pair<Long, Long> {
    val meter = remember(group.id) { TransferRate() }
    val latest by rememberUpdatedState(group)
    var result by remember(group.id) { mutableStateOf(0L to -1L) }
    LaunchedEffect(group.id) {
        while (isActive) {
            val value = latest
            meter.sample(SystemClock.elapsedRealtime(), value.sent, value.active || value.blocked == 0 && value.completed < value.count)
            result = meter.bytesPerSecond() to meter.secondsRemaining(value.bytes - value.sent, value.bytes >= 0)
            delay(1000)
        }
    }
    return result
}
