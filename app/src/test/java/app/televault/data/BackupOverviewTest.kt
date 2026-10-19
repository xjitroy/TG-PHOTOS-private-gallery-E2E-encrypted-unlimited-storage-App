package app.televault.data

import org.junit.Assert.*
import org.junit.Test

class BackupOverviewTest {
    private fun job(id: String, batch: String, count: Int, phase: String = "WAITING", running: Boolean = false, bytes: Long = count * 100L, download: Boolean = false) = DriveTransfer(id, id, phase, 0, 100, download, running, batch, batch, count, bytes, 100)

    @Test fun totalsIncludeCompletedFilesAndAllFoldersButNotDownloads() {
        val camera = (0 until 227).map {
            job(
                "camera-$it",
                "Camera",
                227,
                if (it < 24) {
                    "COMPLETE"
                } else if (it == 24) {
                    "UPLOADING"
                } else {
                    "WAITING"
                },
                running = it == 24
            )
        }
        val screenshots = (0 until 1276).map { job("screen-$it", "Screenshots", 1276) }
        val whatsApp = (0 until 129).map { job("chat-$it", "WhatsApp", 129) }
        val result = backupOverview(camera + screenshots + whatsApp + job("d", "Download", 50, download = true), BackupOverview())
        assertEquals(1632L, result.count)
        assertEquals(24L, result.completed)
        assertEquals("Camera", result.active?.id)
        assertEquals(3, result.groups.size)
    }

    @Test fun finishingFolderKeepsSessionDenominatorWhileOtherFoldersContinue() {
        val jobs = listOf(job("a", "Camera", 2, "UPLOADING", true), job("b", "Screenshots", 3))
        val first = backupOverview(jobs, BackupOverview())
        val next = backupOverview(listOf(jobs[0].copy(phase = "COMPLETE", running = false), jobs[1].copy(phase = "UPLOADING", running = true)), first)
        assertEquals(5L, next.count)
        assertEquals(4L, next.completed)
        assertEquals(2, next.groups.size)
        assertEquals(100, next.groups.single { it.id == "Camera" }.percent)
        val pruned = backupOverview(listOf(jobs[1]), next)
        assertEquals(5L, pruned.count)
    }

    @Test fun missingOrCanceledWorkIsNeverReportedAsSuccessful() {
        val jobs = listOf(job("a", "Camera", 1), job("b", "Screenshots", 1))
        val previous = backupOverview(jobs, BackupOverview())
        val missing = backupOverview(listOf(jobs[1]), previous)
        assertEquals(0L, missing.completed)
        assertEquals(1L, missing.count)
        val canceled = backupOverview(listOf(jobs[0].copy(phase = "CANCELLED"), jobs[1]), previous)
        assertEquals(0L, canceled.completed)
        assertEquals(1L, canceled.blocked)
    }

    @Test fun newUnrelatedBatchResetsSessionAndEmptyLockStateClearsIt() {
        val first = backupOverview(listOf(job("a", "Camera", 5)), BackupOverview())
        val next = backupOverview(listOf(job("b", "New folder", 1)), first)
        assertEquals(1L, next.count)
        assertEquals(0L, backupOverview(emptyList(), first).count)
        assertEquals(0L, backupOverview(listOf(job("x", "download", 1, download = true)), first).count)
    }

    @Test fun unknownBytesUseConfirmedFileCountsAndCommitIsNotCompletion() {
        val unknown = job("a", "Camera", 2, "COMMITTING_INDEX", true, bytes = -1)
        val result = backupOverview(listOf(unknown), BackupOverview())
        assertEquals(-1L, result.bytes)
        assertEquals(1L, result.completed)
        assertEquals(50, result.percent)
        val known = backupOverview(listOf(unknown.copy(batchCount = 1, batchBytes = 100)), BackupOverview())
        assertEquals(99, known.percent)
    }

    @Test fun finalCompletionStaysVisibleAndLargeCountsDoNotOverflow() {
        val job = job("a", "Camera", 1, "UPLOADING", true)
        val first = backupOverview(listOf(job), BackupOverview())
        val done = backupOverview(listOf(job.copy(phase = "COMPLETE")), first)
        assertEquals(100, done.percent)
        assertEquals(1L, done.completed)
        val huge = BackupOverview(listOf(TransferOverview("a", "A", Int.MAX_VALUE, 0, Long.MAX_VALUE, 0, false, false, 0), TransferOverview("b", "B", Int.MAX_VALUE, 0, Long.MAX_VALUE, 0, false, false, 0)))
        assertEquals(4294967294L, huge.count)
        assertEquals(Long.MAX_VALUE, huge.bytes)
        assertEquals(0, huge.percent)
    }

    @Test fun screenshotCountsAndDataPercentageHaveDifferentDenominators() {
        val jobs = (0 until 734).map { i ->
            job("camera-$i", "Camera", 734, if (i < 731) "COMPLETE" else "CAMERA_VIDEO_LIMIT", bytes = 10000).copy(fileBytes = if (i < 731) 1 else 333)
        }
        val waiting = backupOverview(jobs, BackupOverview())
        assertEquals(731L, waiting.completed)
        assertEquals(3L, waiting.blocked)
        assertEquals(90, waiting.percent)
        val complete = backupOverview(jobs.map { it.copy(phase = "COMPLETE") }, waiting)
        assertEquals(100, complete.percent)
        assertEquals(0L, complete.blocked)
    }

    @Test fun checkingAnExistingDeviceCopyIsActiveAndNeverCountsAsUploaded() {
        val checking = backupOverview(listOf(job("a", "Camera", 1, "VERIFYING_DEVICE", true)), BackupOverview())
        assertNotNull(checking.active)
        assertEquals(0L, checking.blocked)
        assertEquals(0L, checking.completed)
        assertEquals(0, checking.percent)
    }
}
