package app.televault.ui

/** Presentation only: backed-up phone files remain device files; cloud copies are not added here. */
internal fun primaryDeviceAlbum(id: String, folder: String?, section: String, collage: Boolean): Boolean {
    if (id == "device:all" && collage || section in setOf("Favorites", "Videos")) return true
    val name = folder?.substringAfterLast('/')?.lowercase(java.util.Locale.ROOT) ?: return false
    return name in setOf("camera", "screenshots", "screen shots", "screen recordings", "screenrecord", "screenrecords", "screenrecorder")
}
