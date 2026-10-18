package app.televault.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ViewerNavigationTest {
    @Test fun rapidRequestsDoNotQueueHundredsOfNativePageChanges() = runBlocking {
        val moved = mutableListOf<Int>()
        val settled = mutableListOf<Int>()
        val owner = ViewerNavigation(this, { index, _ -> moved += index }, { throw AssertionError(it) })
        repeat(1000) { index -> owner.select(index, false) { settled += index } }
        yield()
        assertEquals(listOf(999), moved)
        assertEquals(listOf(999), settled)
    }

    @Test fun cancelledOldMoveCannotFinishANewGesture() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var oldSettled = false
        var newestSettled = false
        val owner = ViewerNavigation(this, { index, _ ->
            if (index == 0) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }, { throw AssertionError(it) })
        owner.select(0, false) { oldSettled = true }
        entered.await()
        owner.cancel() // a new drag starts before a new final target exists
        yield()
        assertFalse(oldSettled)
        owner.select(99, false) { newestSettled = true }
        yield()
        assertTrue(newestSettled)
    }

    @Test fun failedPagerMoveReleasesPreviewAndReportsWithoutKillingScope() = runBlocking {
        val errors = mutableListOf<Exception>()
        var settled = 0
        val owner = ViewerNavigation(this, { index, _ -> if (index == 1) throw IllegalStateException("retired layout") }, { errors += it })
        owner.select(1, false) { settled++ }
        yield()
        assertEquals(1, errors.size)
        assertEquals(1, settled)
        owner.select(2, true) { settled++ }
        yield()
        assertEquals(2, settled)
    }

    @Test fun viewerDisposalCancelsPendingMoveWithoutLateCompletion() = runBlocking {
        var settled = false
        val entered = CompletableDeferred<Unit>()
        val owner = ViewerNavigation(this, { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }, { throw AssertionError(it) })
        owner.select(3, true) { settled = true }
        entered.await()
        owner.cancel()
        yield()
        assertFalse(settled)
    }
}
