package app.televault.data

import app.televault.domain.ActivityProgress

/** Aggregates are computed once off the UI thread, not in getters during composition.
 * Session display only. A disappearing folder is finished only after a confirmed COMPLETE job. */
internal data class BackupOverview(val groups: List<TransferOverview> = emptyList()) {
    val count: Long = groups.sumOf { it.count.toLong() }
    val completed: Long = groups.sumOf { it.completed.toLong() }
    val blocked: Long = groups.sumOf { it.blocked.toLong() }
    val active: TransferOverview? = groups.firstOrNull { it.active }
    val bytes: Long = if (groups.all { it.bytes >= 0 }) saturatedSum(groups.map { it.bytes }) else -1
    val sent: Long = saturatedSum(groups.map { it.sent })
    val percent: Int = ActivityProgress.percent(if (bytes > 0) sent else completed, if (bytes > 0) bytes else count).coerceAtMost(if (completed < count) 99 else 100)
}

private fun saturatedSum(values: List<Long>): Long = values.fold(0L) { sum, value -> sum + value.coerceIn(0, Long.MAX_VALUE - sum) }

internal fun backupOverview(jobs: List<DriveTransfer>, previous: BackupOverview): BackupOverview {
    val uploads = jobs.filter { !it.download }
    if (uploads.isEmpty()) return BackupOverview()
    val current = transferOverviews(uploads)
    val currentIds = current.map { it.id }.toSet()
    val continuing = current.isEmpty() || previous.groups.any { it.completed < it.count && it.id in currentIds }
    if (!continuing) return BackupOverview(current)
    val finishedIds = uploads.filter { it.phase == "COMPLETE" }.map { it.batchId.ifBlank { it.id } }.toSet()
    val retained = previous.groups.filter { it.id !in currentIds }.mapNotNull { old ->
        when {
            old.completed == old.count -> old
            old.id in finishedIds -> old.copy(completed = old.count, sent = old.bytes.coerceAtLeast(0), active = false, transferring = false, blocked = 0)
            else -> null // Removed/unknown state is not proof of backup success.
        }
    }
    return BackupOverview(current + retained)
}
