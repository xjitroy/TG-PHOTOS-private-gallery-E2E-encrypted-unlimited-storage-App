package app.televault

import app.televault.data.finishUploadDrain
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class UploadDrainHandoffTest {
    @Test fun enqueueAfterFinalInspectionSeesReleasedRunner() = runBlocking {
        val edits = Mutex()
        val runner = Mutex(locked = true)
        val inspecting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val drain = launch {
            finishUploadDrain(edits, runner) {
                inspecting.complete(Unit)
                finish.await()
            }
        }
        inspecting.await()
        var admitted = false
        val enqueue = launch {
            edits.withLock {
                admitted = runner.tryLock()
                if (admitted) runner.unlock()
            }
        }
        yield()
        assertFalse(enqueue.isCompleted)
        finish.complete(Unit)
        drain.join()
        enqueue.join()
        assertTrue(admitted)
    }

    @Test fun enqueueBeforeFinalInspectionIsIncluded() = runBlocking {
        val edits = Mutex(locked = true)
        val runner = Mutex(locked = true)
        var pending = false
        var observed = false
        val drain = launch { finishUploadDrain(edits, runner) { observed = pending } }
        yield()
        pending = true
        edits.unlock()
        drain.join()
        assertTrue(observed)
        assertTrue(runner.tryLock())
        runner.unlock()
    }

    @Test fun cancelledDrainStillInspectsAndReleasesAdmission() = runBlocking {
        val edits = Mutex()
        val runner = Mutex(locked = true)
        var inspected = false
        val drain = launch {
            currentCoroutineContext().cancel()
            finishUploadDrain(edits, runner) { inspected = true }
        }
        drain.join()
        assertTrue(inspected)
        assertTrue(runner.tryLock())
        runner.unlock()
    }

    @Test fun failedFinalInspectionCannotDeadlockLaterUploads() = runBlocking {
        val edits = Mutex()
        val runner = Mutex(locked = true)
        try {
            finishUploadDrain(edits, runner) { error("simulated metadata failure") }
            fail()
        } catch (_: IllegalStateException) { }
        assertTrue(runner.tryLock())
        runner.unlock()
        assertTrue(edits.tryLock())
        edits.unlock()
    }
}
