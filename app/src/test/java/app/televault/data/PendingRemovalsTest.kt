package app.televault.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PendingRemovalsTest {
    @Test fun failureOrCancellationRestoresOnlyUnchangedPresentation() = runBlocking {
        val pending = PendingRemovals()
        try {
            pending.run(setOf("photo")) {
                assertEquals(setOf("photo"), pending.ids.value)
                error("Remote write failed")
            }
        } catch (_: IllegalStateException) { }
        assertTrue(pending.ids.value.isEmpty())
        val started = CompletableDeferred<Unit>()
        val job = launch {
            pending.run(setOf("file")) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(pending.ids.value.isEmpty())
    }

    @Test fun overlappingOperationsAndStaleCompletionCannotUnhideAnotherRequest() {
        val pending = PendingRemovals()
        val first = pending.begin(setOf("one", "shared"))
        val second = pending.begin(setOf("shared", "two"))
        pending.end(first)
        assertEquals(setOf("shared", "two"), pending.ids.value)
        pending.clear()
        val third = pending.begin(setOf("shared"))
        pending.end(second)
        assertEquals(setOf("shared"), pending.ids.value)
        pending.end(third)
        assertTrue(pending.ids.value.isEmpty())
    }

    @Test fun largeOverlappingBatchesRetainUniqueIdsUntilTheirLastOwnerFinishes() {
        val pending = PendingRemovals()
        val ids = (0 until 10000).map { "photo:$it" }.toSet()
        val requests = (0 until 100).map { pending.begin(ids) }
        requests.dropLast(1).forEach { pending.end(it) }
        assertEquals(ids, pending.ids.value)
        pending.end(requests.last())
        assertTrue(pending.ids.value.isEmpty())
    }

    @Test fun onlyKnownQueuedPurgePhasesHideRowsAndFailureOrCooldownRestoresThem() {
        for (phase in listOf("WAITING", "RETRYING", "INTERRUPTED", "WAITING_TELEGRAM", "WAITING_UNLOCK")) assertEquals("photo", PendingRemovals.queuedPurge("purge:photo", phase))
        for (phase in listOf("FAILED", "CANCELLED", "COMPLETE", "WAITING_TRASH_COOLDOWN", "unknown")) assertNull(PendingRemovals.queuedPurge("purge:photo", phase))
        assertNull(PendingRemovals.queuedPurge("upload:photo", "WAITING"))
        assertNull(PendingRemovals.queuedPurge("purge:", "WAITING"))
    }
}
