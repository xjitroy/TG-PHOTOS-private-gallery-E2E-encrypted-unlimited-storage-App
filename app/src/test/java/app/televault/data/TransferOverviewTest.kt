package app.televault.data

import org.junit.Assert.*
import org.junit.Test

class TransferOverviewTest {
    private fun job(id: String, phase: String, progress: Long = 0, total: Long = 100, running: Boolean = false) = DriveTransfer(id, id, phase, progress, total, false, running, "batch", "Camera", 3, 300, 100)

    @Test fun folderTotalStaysStableAcrossPhaseChangesAndPrunedCompletedJobs() {
        val encrypting = transferOverviews(listOf(job("a", "ENCRYPTING", 100, running = true), job("b", "WAITING"), job("c", "WAITING"))).single()
        assertEquals(0, encrypting.percent)
        val halfway = transferOverviews(listOf(job("a", "UPLOADING", 50, running = true), job("b", "WAITING"), job("c", "WAITING"))).single()
        assertEquals(16, halfway.percent)
        val pruned = transferOverviews(listOf(job("b", "UPLOADING", 50, running = true), job("c", "WAITING"))).single()
        assertEquals(3, pruned.count)
        assertEquals(1, pruned.completed)
        assertEquals(50, pruned.percent)
    }

    @Test fun blockedCanceledAndUnknownSizeDoNotPretendToBeFinished() {
        val blocked = transferOverviews(listOf(job("a", "FAILED"), job("b", "CANCELLED"), job("c", "WAITING_UNLOCK"))).single()
        assertEquals(0, blocked.completed)
        assertEquals(0, blocked.percent)
        assertFalse(blocked.active)
        assertEquals(3, blocked.blocked)
        val unknown = transferOverviews(listOf(job("a", "UPLOADING", 20, running = true).copy(batchBytes = -1, fileBytes = -1))).single()
        assertEquals(-1, unknown.percent)
        assertTrue(unknown.active)
    }

    @Test fun commitAndMultipartProgressUsePayloadBytesAndDoNotRestartPercent() {
        val multipart = transferOverviews(listOf(job("a", "UPLOADING", 110, 220, true), job("b", "WAITING"), job("c", "WAITING"))).single()
        assertEquals(50L, multipart.sent)
        val committing = transferOverviews(listOf(job("a", "COMMITTING_INDEX", 220, 220, true), job("b", "WAITING"), job("c", "WAITING"))).single()
        assertEquals(100L, committing.sent)
        assertEquals(0, committing.completed)
        assertTrue(transferOverviews(listOf(job("a", "COMPLETE"))).isEmpty())
    }
}
