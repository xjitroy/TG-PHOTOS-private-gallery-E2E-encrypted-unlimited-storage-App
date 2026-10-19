package app.televault.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhotoLoadPriorityTest {
    @Test fun queuedSpeculationRechecksSelectionBeforeOpeningNativeReads() {
        val priority = PhotoLoadPriority()
        assertTrue(priority.permits("tile"))
        val token = priority.begin("story")
        assertFalse(priority.permits("tile"))
        assertTrue(priority.permits("story"))
        priority.ready(token)
        assertTrue(priority.permits("tile"))
    }

    @Test fun selectedPhotoProceedsWhileSpeculationWaitsForAuthenticatedPixels() = runBlocking {
        val priority = PhotoLoadPriority()
        val token = priority.begin("selected")
        val neighbor = async(start = CoroutineStart.UNDISPATCHED) { priority.await("neighbor") }
        assertFalse(neighbor.isCompleted)
        withTimeout(1000) { priority.await("selected") }
        priority.ready(token)
        withTimeout(1000) { neighbor.await() }
    }

    @Test fun lateCompletionOfAnOldPhotoCannotReleaseTheNewSelection() = runBlocking {
        val priority = PhotoLoadPriority()
        val old = priority.begin("old")
        val current = priority.begin("new")
        val speculation = async(start = CoroutineStart.UNDISPATCHED) { priority.await("old") }
        priority.ready(old)
        yield()
        assertFalse(speculation.isCompleted)
        priority.ready(current)
        withTimeout(1000) { speculation.await() }
    }

    @Test fun lockingOrLeavingTheViewerReleasesOptionalWorkWithoutRetainingAnIdentity() = runBlocking {
        val priority = PhotoLoadPriority()
        priority.begin("private-id")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { priority.await("other") }
        assertFalse(waiting.isCompleted)
        priority.clear()
        withTimeout(1000) { waiting.await() }
        withTimeout(1000) { priority.await("any") }
    }
}
