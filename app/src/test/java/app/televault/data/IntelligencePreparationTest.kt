package app.televault.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IntelligencePreparationTest {
    @Test fun blockedRestoreCannotStarveOtherEnginesAndRequestsRetry() = runBlocking {
        val textReady = CompletableDeferred<Unit>()
        val prepared = prepareIntelligence(
            listOf(true, true, true),
            1000,
            listOf(
                {
                    textReady.await()
                    awaitCancellation()
                },
                { true },
                { throw IllegalStateException("model unavailable") }
            )
        ) { if (it == 1) textReady.complete(Unit) }
        assertEquals(listOf(false, true, false), prepared)
    }

    @Test fun disabledEngineIsNeverPreparedAndNativeFailureIsIsolated() = runBlocking {
        val prepared = prepareIntelligence(
            listOf(false, true, true),
            prepare = listOf(
                { error("disabled engine executed") },
                { throw UnsatisfiedLinkError("native library unavailable") },
                { true }
            )
        )
        assertEquals(listOf(false, false, true), prepared)
    }

    @Test fun sessionCancellationStillCancelsEveryPreparation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val job = launch {
            prepareIntelligence(
                listOf(true),
                prepare = listOf({
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                })
            )
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped)
        assertTrue(job.isCancelled)
    }
}
