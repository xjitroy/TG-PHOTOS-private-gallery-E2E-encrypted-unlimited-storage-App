package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.televault.domain.LibraryPath

/** Real readable-item counts only; no invented total-device capacity or inaccessible data. */
@Composable
internal fun FileCategoryCards(files: List<MediaEntry>, counts: Map<String, Int>? = null, choose: (String) -> Unit) {
    val actualCounts = counts ?: remember(files) { files.groupingBy { libraryCategory(it) }.eachCount() }
    val categories = listOf("Photos" to Icons.Outlined.Image, "Videos" to Icons.Outlined.PlayCircle, "Audio" to Icons.Outlined.MusicNote, "Documents" to Icons.Outlined.Description, "Apps" to Icons.Outlined.Android, "Archives" to Icons.Outlined.FolderZip, "Other files" to Icons.Outlined.Inventory2, "Recent" to Icons.Outlined.History)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (type, icon) ->
                    val colors = folderColors(type)
                    FilledTonalButton({ choose(type) }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.container, contentColor = MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(icon, null, Modifier.size(24.dp), tint = colors.accent)
                            Text(if (type == "Recent") label("Recent", "সাম্প্রতিক") else sectionLabel(type), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Text("${if (type == "Recent") files.size else actualCounts[type] ?: 0}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
