package app.televault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal data class FolderColors(val accent: Color, val container: Color)

private val darkFolderAccents = listOf(Color(0xFFA9C7FF), Color(0xFF89D7C6), Color(0xFFDCB8FF), Color(0xFFFFC58F), Color(0xFFFFB4C5), Color(0xFFB9D995), Color(0xFF8FD5ED), Color(0xFFD1C2FF))
private val lightFolderAccents = listOf(Color(0xFF285DA0), Color(0xFF006B59), Color(0xFF795095), Color(0xFF89511F), Color(0xFF994258), Color(0xFF4C6928), Color(0xFF12677D), Color(0xFF62539D))

/** Stable identity-based accents, with readable tones for both Material and translucent Glass. */
@Composable
internal fun folderColors(identity: String): FolderColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val index = when (identity) {
        "Photos" -> 0
        "Videos" -> 2
        "Audio" -> 4
        "Documents" -> 1
        "Apps" -> 5
        "Archives" -> 3
        "Other files" -> 7
        "Recent" -> 6
        else -> (identity.hashCode() and Int.MAX_VALUE) % 8
    }
    val accent = (if (dark) darkFolderAccents else lightFolderAccents)[index]
    return FolderColors(accent, lerp(scheme.surfaceContainerLow, accent, if (dark) .10f else .09f))
}
