package app.televault.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExclusionRestoreTest {
    @Test fun emptyAuthenticatedEnumerationCanCommitWithoutWaitingForUploads() = runBlocking {
        var committed = false
        restoreExclusionRecords(emptyList<Int>(), { false }, { error("not reached") }, { committed = true }, { done, total ->
            assertEquals(0, done)
            assertEquals(0, total)
        })
        assertTrue(committed)
    }

    @Test fun authenticationFailureNeverCommitsPartialRestore() = runBlocking {
        val saved = mutableSetOf<Int>()
        var committed = false
        try {
            restoreExclusionRecords(listOf(1, 2, 3), saved::contains, {
                if (it == 2) error("authentication")
                saved += it
            }, { committed = true }, { _, _ -> })
            fail("must propagate authentication failure")
        } catch (_: IllegalStateException) { }
        assertEquals(setOf(1), saved)
        assertFalse(committed)
    }

    @Test fun interruptedRestoreResumesFromCheckpointAndCommitsOnlyAfterAllRecords() = runBlocking {
        val saved = mutableSetOf<Int>()
        var committed = false
        try {
            restoreExclusionRecords((1..100).toList(), saved::contains, {
                if (it == 20) throw CancellationException()
                saved += it
            }, { committed = true }, { _, _ -> })
        } catch (_: CancellationException) { }
        assertEquals(19, saved.size)
        assertFalse(committed)
        var applied = 0
        restoreExclusionRecords((1..100).toList(), saved::contains, {
            assertFalse(it in saved)
            saved += it
            applied++
        }, { committed = true }, { done, total -> assertTrue(done <= total) })
        assertTrue(committed)
        assertEquals(81, applied)
        assertEquals(100, saved.size)
    }
}
