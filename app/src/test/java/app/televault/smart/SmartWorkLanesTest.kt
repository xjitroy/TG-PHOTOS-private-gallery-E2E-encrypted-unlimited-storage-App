package app.televault.smart

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SmartWorkLanesTest {
    @Test fun slowReplacementDownloadKeepsPreviousSearchUsable() = runBlocking {
        val lanes = SmartWorkLanes()
        val downloading = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            lanes.download {
                downloading.complete(Unit)
                finish.await()
            }
        }
        downloading.await()
        try {
            assertEquals("previous result", withTimeout(1000) { lanes.runtime { "previous result" } })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun activationWaitsForOwnedInferenceAfterDownloadFinishes() = runBlocking {
        val lanes = SmartWorkLanes()
        val reading = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        val copied = CompletableDeferred<Unit>()
        var activations = 0
        val old = launch {
            lanes.runtime {
                reading.complete(Unit)
                finishRead.await()
            }
        }
        reading.await()
        val replacement = launch {
            lanes.download {
                copied.complete(Unit)
                lanes.runtime { activations++ }
            }
        }
        copied.await()
        yield()
        assertEquals(0, activations)
        finishRead.complete(Unit)
        old.join()
        replacement.join()
        assertEquals(1, activations)
    }

    @Test fun cancellingDownloadDoesNotCancelAnExistingRuntimeOwner() = runBlocking {
        val lanes = SmartWorkLanes()
        val reading = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        val old = async {
            lanes.runtime {
                reading.complete(Unit)
                finishRead.await()
                "kept"
            }
        }
        reading.await()
        val downloading = CompletableDeferred<Unit>()
        val pending = launch {
            lanes.download {
                downloading.complete(Unit)
                awaitCancellation()
            }
        }
        downloading.await()
        pending.cancelAndJoin()
        assertTrue(old.isActive)
        finishRead.complete(Unit)
        assertEquals("kept", old.await())
    }

    @Test fun batchCleanupCannotAbortOrWaitForAnInstallingModel() = runBlocking {
        val lanes = SmartWorkLanes()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var stopped = false
        val install = launch {
            lanes.runtime {
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()
        assertFalse(withTimeout(1000) { lanes.idle { stopped = true } })
        assertFalse(stopped)
        assertTrue(install.isActive)
        finish.complete(Unit)
        install.join()
        assertTrue(lanes.idle { stopped = true })
        assertTrue(stopped)
    }
}
