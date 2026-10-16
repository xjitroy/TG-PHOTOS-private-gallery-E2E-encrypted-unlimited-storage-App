package app.televault.data

/** A manifest carries its encrypted thumbnail. Counts describe verified catalog work, not bandwidth. */
data class CloudRestoreStatus(val scope: String, val completed: Long, val total: Long, val firstRestore: Boolean) {
    val percent: Int get() = app.televault.domain.ActivityProgress.percent(completed, total)
}
