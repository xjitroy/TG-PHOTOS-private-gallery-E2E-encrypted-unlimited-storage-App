package app.televault.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineDownloadsSheet(model: DriveViewModel, close: () -> Unit) {
    val offline by model.offlineDownloads.collectAsStateWithLifecycle()
    val snapshot by model.contentState.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val files = remember(snapshot.files, offline.ids) { snapshot.files.filter { it.id in offline.ids }.sortedByDescending { it.date } }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label("Encrypted offline downloads", "এনক্রিপ্টেড অফলাইন ডাউনলোড"), style = MaterialTheme.typography.titleLarge)
            Text(label("Kept until you remove them. Unlock is still required. Private Vault media is never pinned.", "আপনি না সরানো পর্যন্ত থাকবে। খুলতে আনলক প্রয়োজন। Private Vault-এর মিডিয়া এখানে রাখা হয় না।"), style = MaterialTheme.typography.bodySmall)
            Text(label("Used ${sizeLabel(offline.bytes)} · Limit ${sizeLabel(offline.quota)}", "ব্যবহৃত ${sizeLabel(offline.bytes)} · সীমা ${sizeLabel(offline.quota)}"), style = MaterialTheme.typography.titleSmall)
            Text(label("This is a maximum allowance, not space already used. Temporary viewed media is managed separately in Storage & Cache.", "এটি সর্বোচ্চ সীমা, ইতিমধ্যে ব্যবহৃত জায়গা নয়। দেখা মিডিয়ার অস্থায়ী কপি Storage & Cache-এ আলাদাভাবে নিয়ন্ত্রিত হয়।"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(128L to "128 MB", 512L to "512 MB", 2048L to "2 GB", 8192L to "8 GB").forEach { (mb, title) ->
                    FilterChip(offline.quota == mb * 1024 * 1024, { model.offlineQuota(mb * 1024 * 1024) }, enabled = offline.activeId == null && offline.bytes <= mb * 1024 * 1024, label = { Text(title) })
                }
            }
            offline.activeId?.let { id ->
                Text(snapshot.files.firstOrNull { it.id == id }?.name ?: label("Downloading", "ডাউনলোড হচ্ছে"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton({ model.cancelOfflineDownload() }) { Text(label("Cancel download", "ডাউনলোড বাতিল")) }
                LinearProgressIndicator(progress = { if (offline.total > 0) offline.progress.toFloat() / offline.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text(label("Verifies the complete encrypted copy before marking it available.", "সম্পূর্ণ এনক্রিপ্টেড কপি যাচাই হওয়ার পর available দেখাবে।"), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (files.isEmpty()) item { Text(label("No offline copies yet. Use a cloud file's menu to keep it offline.", "এখনও অফলাইন কপি নেই। ক্লাউড ফাইলের menu থেকে offline রাখুন।"), Modifier.padding(vertical = 16.dp)) }
                items(files, key = { it.id }) { file ->
                    ListItem(headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(sizeLabel(file.size)) }, leadingContent = { Icon(Icons.Outlined.OfflinePin, null) }, trailingContent = {
                        IconButton({ model.removeOffline(setOf(file.id)) }, enabled = offline.activeId == null) { Icon(Icons.Outlined.DeleteOutline, label("Remove offline copy", "অফলাইন কপি সরান")) }
                    })
                }
            }
            TextButton({ confirmRemove = true }, enabled = files.isNotEmpty() && offline.activeId == null) { Text(label("Remove this account's offline copies", "এই অ্যাকাউন্টের অফলাইন কপিগুলি সরান")) }
        }
    }
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text(label("Remove offline copies?", "অফলাইন কপি সরাবেন?")) }, text = { Text(label("Your cloud files stay. Only this account's encrypted offline downloads are removed.", "ক্লাউড ফাইল থাকবে। শুধু এই অ্যাকাউন্টের এনক্রিপ্টেড অফলাইন কপি সরবে।")) }, confirmButton = {
            TextButton({
                model.removeOffline(offline.ids)
                confirmRemove = false
            }) { Text(label("Remove", "সরান")) }
        }, dismissButton = { TextButton({ confirmRemove = false }) { Text(label("Cancel", "বাতিল")) } })
    }
}
