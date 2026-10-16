package app.televault.data

import app.televault.domain.ActivityProgress

internal data class TransferOverview(val id: String, val name: String, val count: Int, val completed: Int, val bytes: Long, val sent: Long, val active: Boolean, val transferring: Boolean, val blocked: Int) {
    val percent: Int get() = if (bytes > 0) ActivityProgress.percent(sent, bytes).coerceAtMost(if (completed < count) 99 else 100) else -1
}

/** Only authenticated job display fields enter this calculation; waiting bytes are not sent. */
internal fun transferOverviews(jobs: List<DriveTransfer>): List<TransferOverview> = jobs.groupBy { it.batchId.ifBlank { it.id } }.mapNotNull { (id, group) ->
    val pending = group.filter { it.phase != "COMPLETE" }
    if (pending.isEmpty()) return@mapNotNull null
    val plan = group.first()
    val count = plan.batchCount.takeIf { it > 0 } ?: group.size
    val known = if (plan.batchCount > 0) plan.batchBytes >= 0 else group.all { it.fileBytes >= 0 }
    val bytes = if (known) plan.batchBytes.takeIf { plan.batchCount > 0 } ?: group.sumOf { it.fileBytes } else -1L
    fun remaining(job: DriveTransfer): Long {
        val size = job.fileBytes.coerceAtLeast(0)
        return when (job.phase) {
            "COMMITTING_INDEX", "COMPLETE" -> 0L
            "UPLOADING", "DOWNLOADING", "EXPORTING" -> if (job.total > 0) (size * (1.0 - (job.progress.toDouble() / job.total).coerceIn(0.0, 1.0))).toLong() else size
            else -> size
        }
    }
    val active = pending.any { it.running && ActivityProgress.active(it.phase) }
    val transferring = pending.any { it.running && it.phase in setOf("UPLOADING", "DOWNLOADING", "EXPORTING") }
    TransferOverview(id, plan.batchName.ifBlank { plan.name }, count, (count - pending.size).coerceAtLeast(0), bytes, if (known) (bytes - pending.sumOf(::remaining)).coerceIn(0, bytes) else 0, active, transferring, pending.count { !ActivityProgress.active(it.phase) && it.phase != "WAITING" })
}
