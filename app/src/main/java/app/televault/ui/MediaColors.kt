package app.televault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Consistent semantic accents across Files, Albums, editors and Vault, with paired contrast roles. */
@Composable internal fun mediaAccent(mime: String) = when {
    mime.startsWith("video/", ignoreCase = true) -> MaterialTheme.colorScheme.onTertiaryContainer
    mime.startsWith("audio/", ignoreCase = true) -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.onPrimaryContainer
}

@Composable internal fun mediaAccentContainer(mime: String) = when {
    mime.startsWith("video/", ignoreCase = true) -> MaterialTheme.colorScheme.tertiaryContainer
    mime.startsWith("audio/", ignoreCase = true) -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.primaryContainer
}
internal fun mediaTypeIcon(mime: String) = when {
    mime.startsWith("image/", ignoreCase = true) -> Icons.Outlined.Image
    mime.startsWith("video/", ignoreCase = true) -> Icons.Outlined.VideoFile
    mime.startsWith("audio/", ignoreCase = true) -> Icons.Outlined.AudioFile
    mime.equals("application/pdf", ignoreCase = true) -> Icons.Outlined.PictureAsPdf
    mime.equals("application/vnd.android.package-archive", ignoreCase = true) -> Icons.Outlined.Android
    mime.lowercase(java.util.Locale.ROOT) in setOf("application/zip", "application/x-7z-compressed", "application/x-rar-compressed", "application/vnd.rar", "application/gzip", "application/x-tar") -> Icons.Outlined.FolderZip
    else -> Icons.Outlined.Description
}

internal fun libraryCategory(item: MediaEntry): String {
    val mime = item.mime.lowercase(java.util.Locale.ROOT)
    val extension = item.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
    return when {
        mime == "application/vnd.android.package-archive" || extension == "apk" -> "Apps"
        mime in setOf("application/zip", "application/x-7z-compressed", "application/x-rar-compressed", "application/vnd.rar", "application/gzip", "application/x-tar") || extension in setOf("zip", "7z", "rar", "gz", "tar", "bz2", "xz") -> "Archives"
        else -> app.televault.domain.LibraryPath.category(mime)
    }
}
