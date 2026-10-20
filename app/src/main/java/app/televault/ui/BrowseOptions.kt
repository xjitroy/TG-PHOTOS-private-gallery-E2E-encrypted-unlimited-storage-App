package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Secondary controls never displace the library. The sheet opens only on explicit user action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseOptions(
    summary: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    reveal: Boolean = false,
    dismissed: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reveal) { if (reveal) open = true }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ open = true }) {
            Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label("View options", "দেখার অপশন"))
        }
    }
    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = {
            open = false
            dismissed()
        }, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.86f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label("View options", "দেখার অপশন"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton({
                        scope.launch {
                            sheet.hide()
                            open = false
                            dismissed()
                        }
                    }) { Icon(Icons.Outlined.Close, label("Close options", "অপশন বন্ধ করুন")) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        }
    }
}
