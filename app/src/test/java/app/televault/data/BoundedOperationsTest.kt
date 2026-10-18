package app.televault.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BoundedOperationsTest {
    @Test fun thousandRepeatedRequestsShareOneJobAndDifferentWorkIsBounded() = runBlocking {
        var rejected = 0
        var started = 0
        val release = CompletableDeferred<Unit>()
        val queue = BoundedOperations<String>(this, { rejected++ }, 8, 2)
        val first = queue.submit("same") {
            started++
            release.await()
        }
        repeat(1000) { assertSame(first, queue.submit("same") { error("Duplicate ran") }) }
        val others = (1..7).map {
            queue.submit("$it") {
                started++
                release.await()
            }
        }
        yield()
        assertEquals(2, started)
        assertTrue(queue.submit("overflow") { error("Overflow ran") }.isCancelled)
        assertEquals(1, rejected)
        release.complete(Unit)
        (others + first).joinAll()
        assertEquals(8, started)
        queue.submit("after") { started++ }.join()
        assertEquals(9, started)
    }

    @Test fun lockCancelsWaitingWorkAndOldCompletionCannotRemoveNewOwnership() = runBlocking {
        val queue = BoundedOperations<String>(this, { error("Unexpected overload") }, 2, 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = queue.submit("same") {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        entered.await()
        val waiting = queue.submit("waiting") { error("Canceled work ran") }
        queue.cancel()
        val current = queue.submit("same") { }
        release.complete(Unit)
        old.join()
        waiting.join()
        current.join()
        assertTrue(waiting.isCancelled)
        assertFalse(current.isCancelled)
        assertNotSame(old, current)
        queue.submit("same") { }.join()
    }

    @Test fun failedWorkReleasesPermitAndAdmissionSlot() = runBlocking {
        val failure = CompletableDeferred<Throwable>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + parent + CoroutineExceptionHandler { _, error -> failure.complete(error) })
        val queue = BoundedOperations<Int>(scope, { error("Slot leaked") }, 1, 1)
        val job = queue.submit(1) { throw OutOfMemoryError("Synthetic allocation failure") }
        job.join()
        assertTrue(failure.await() is OutOfMemoryError)
        var ran = false
        queue.submit(1) { ran = true }.join()
        assertTrue(ran)
        parent.cancel()
    }
}
