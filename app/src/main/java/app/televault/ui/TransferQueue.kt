package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel

@Composable
internal fun TransferQueue(model: DriveViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.transfers, key = { it.id }) { task ->
            AppSurface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ListItem(headlineContent = { Text(task.name, maxLines = 2) }, supportingContent = {
                    Column {
                        Text(transferLabel(task.phase))
                        if (task.phase == "VIDEO_NOTICE_REQUIRED") TextButton({ model.acknowledgeAutomaticVideo(task.id) }) { Text(label("I understand · upload this video", "বুঝেছি · এই ভিডিও আপলোড করুন")) }
                        if (task.id.startsWith("trash-all:")) Text("${task.progress} / ${task.total}")
                        if (task.total > 0) LinearProgressIndicator(progress = { (task.progress.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }, trailingContent = {
                    Row {
                        IconButton({ model.retry(task.id) }) { Icon(Icons.Outlined.Refresh, label("Retry", "আবার")) }
                        IconButton({ model.cancel(task.id) }) { Icon(Icons.Outlined.Close, label("Cancel", "বাতিল")) }
                    }
                })
            }
        }
    }
}
