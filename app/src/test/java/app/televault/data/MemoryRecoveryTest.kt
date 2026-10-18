package app.televault.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class MemoryRecoveryTest {
    @Test fun allocationFailureReleasesCachesThenRestartsFromTheLatestSource() = runBlocking {
        var attempts = 0
        var released = 0
        val result = flow {
            attempts++
            if (attempts == 1) throw OutOfMemoryError("Synthetic index allocation failure")
            emit("latest verified snapshot")
        }.recoveringMemory(1) { released++ }.single()
        assertEquals("latest verified snapshot", result)
        assertEquals(1, released)
        assertEquals(2, attempts)
    }

    @Test fun ordinaryFailureAndCancellationNeverBecomeMemoryRetries() = runBlocking {
        var released = 0
        for (failure in listOf(IllegalStateException("bad record"), CancellationException("locked"))) {
            try {
                flow<Int> { throw failure }.recoveringMemory(1) { released++ }.collect()
                fail("Failure was swallowed")
            } catch (error: Exception) {
                assertSame(failure, error)
            }
        }
        assertEquals(0, released)
    }

    @Test fun disposalDuringBackoffPreventsAnotherAttempt() = runBlocking {
        var attempts = 0
        val recovered = CompletableDeferred<Unit>()
        val job = launch {
            flow<Int> {
                attempts++
                throw OutOfMemoryError("pressure")
            }.recoveringMemory(10000) { recovered.complete(Unit) }.collect()
        }
        recovered.await()
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }
}
