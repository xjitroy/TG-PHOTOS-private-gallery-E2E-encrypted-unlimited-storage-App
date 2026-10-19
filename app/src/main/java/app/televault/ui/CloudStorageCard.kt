package app.televault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.televault.data.DriveSnapshot

/** Current authenticated original bytes, not a guessed Telegram quota or lifetime traffic. */
@Composable
internal fun CloudStorageCard(state: DriveSnapshot, compact: Boolean = false, onClick: (() -> Unit)? = null, minimal: Boolean = false) {
    val total = remember(state.files) { state.files.distinctBy { it.id }.sumOf { it.size } }
    val trash = remember(state.files) { state.files.filter { it.trashedAt > 0 }.sumOf { it.size } }
    if (minimal) {
        AppSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .44f)) {
            Row(Modifier.padding(horizontal = 14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.EnhancedEncryption, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label("Backup status", "ব্যাকআপ স্ট্যাটাস"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(sizeLabel(total), style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
            }
        }
        return
    }
    AppSurface(Modifier.fillMaxWidth().padding(horizontal = if (compact) 20.dp else 0.dp, vertical = 6.dp).pageEntrance().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) {
        Column(Modifier.padding(if (compact) 14.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EnhancedEncryption, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(label("Encrypted backup", "এনক্রিপ্টেড ব্যাকআপ"), style = MaterialTheme.typography.titleSmall)
                    Text(label("Backed up: ", "ব্যাকআপ: ") + sizeLabel(total), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Outlined.EnhancedEncryption, null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(label("End-to-end encrypted · Your files, your keys", "এন্ড-টু-এন্ড এনক্রিপ্টেড · আপনার ফাইল, আপনার কী"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (!compact) {
                Text(label("${state.files.size} confirmed files · includes ", "${state.files.size}টি নিশ্চিত ফাইল · ট্র্যাশে ") + sizeLabel(trash) + label(" in cloud trash", " রয়েছে"), style = MaterialTheme.typography.bodySmall)
                Text(label("No fixed total quota is advertised by Telegram. Service rules and retention still apply.", "Telegram নির্দিষ্ট মোট কোটা ঘোষণা করে না। সার্ভিসের নিয়ম ও সংরক্ষণসীমা প্রযোজ্য।"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
