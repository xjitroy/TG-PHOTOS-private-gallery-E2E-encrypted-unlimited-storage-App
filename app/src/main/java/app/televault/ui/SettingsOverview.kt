package app.televault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsOverview(open: (String) -> Unit, logout: () -> Unit, modifier: Modifier = Modifier) {
    val entries = listOf("Backup & Sync" to Icons.Outlined.Backup, "Export Cloud library" to Icons.Outlined.FolderZip, "Storage & Cache" to Icons.Outlined.Storage, "Security" to Icons.Outlined.Security, "Player" to Icons.Outlined.PlayCircle, "Theme" to Icons.Outlined.Palette, "Data Usage" to Icons.Outlined.DataUsage, "Help" to Icons.Outlined.HelpOutline, "About" to Icons.Outlined.Info)
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { TelegramStorageWarning() }
        item { Text("TG Photos ${app.televault.BuildConfig.VERSION_NAME}", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
        items(entries, key = { it.first }) { (name, icon) ->
            Surface(Modifier.fillMaxWidth().clickable { open(name) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
            }
        }
        item { OutlinedButton(logout, Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(label("Log out of Telegram", "Telegram থেকে লগআউট")) } }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
