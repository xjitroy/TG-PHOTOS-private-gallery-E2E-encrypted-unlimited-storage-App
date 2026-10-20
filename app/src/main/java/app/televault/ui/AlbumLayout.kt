package app.televault.ui

/** Presentation only: backed-up phone files remain device files; cloud copies are not added here. */
internal fun primaryDeviceAlbum(id: String, folder: String?, section: String, collage: Boolean): Boolean {
    if (id == "device:all" && collage || section in setOf("Favorites", "Videos")) return true
    val name = folder?.substringAfterLast('/')?.lowercase(java.util.Locale.ROOT) ?: return false
    return name in setOf("camera", "screenshots", "screen shots", "screen recordings", "screenrecord", "screenrecords", "screenrecorder")
}

internal const val MORE_ALBUM_PREVIEW_LIMIT = 6

/** Counts rank first; newest content and stable folder identity break equal-count ties. */
internal fun rankMoreAlbums(albums: List<AlbumTile>): List<AlbumTile> = albums
    .map { tile -> tile to (tile.items.maxOfOrNull { maxOf(it.date, it.local?.modified ?: 0L) } ?: 0L) }
    .sortedWith(compareByDescending<Pair<AlbumTile, Long>> { it.first.items.size }.thenByDescending { it.second }.thenBy { it.first.id })
    .map { it.first }

internal fun moreAlbumPreview(ranked: List<AlbumTile>): List<AlbumTile> = ranked.take(MORE_ALBUM_PREVIEW_LIMIT)
