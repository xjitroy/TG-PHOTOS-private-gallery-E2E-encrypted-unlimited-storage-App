package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel

/** A single optional model enriches both existing Search and Memories, never a second search. */
@Composable
internal fun SmartSuggestionControls(model: DriveViewModel) {
    val index by model.scenes.state.collectAsStateWithLifecycle()
    val pack by model.scenes.models.state.collectAsStateWithLifecycle()
    ListItem(headlineContent = { Text("Smart suggestions") }, supportingContent = {
        Text(
            when {
                pack.busy -> "Downloading ${pack.downloaded * 100 / pack.total.coerceAtLeast(1)}% · on-device model"
                index.notice.isNotBlank() -> index.notice
                pack.notice.isNotBlank() -> pack.notice
                !pack.installed -> "Optional 14.25 MB · subjects in Search & Memories"
                index.enabled -> "${index.indexed} photos / video previews checked · processed on this phone"
                else -> "On-device subjects · off"
            }
        )
    }, trailingContent = {
        Switch(index.enabled, { value ->
            model.peopleAction {
                model.scenes.enable(value)
                if (value) model.requestIntelligence()
            }
        })
    })
    if (index.enabled && !pack.installed && !pack.busy) TextButton({ model.scenes.models.install() }, Modifier.padding(horizontal = 16.dp)) { Text("Download / retry · 14.25 MB") }
    if (pack.busy) TextButton({ model.scenes.models.cancel() }, Modifier.padding(horizontal = 16.dp)) { Text("Pause download") }
}
