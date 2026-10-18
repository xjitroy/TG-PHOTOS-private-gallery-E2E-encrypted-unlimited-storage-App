package app.televault

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StartupLoaderTest {
    @Test fun concurrentActivityLoadsShareOneInitialization() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var creates = 0
        val expected = Any()
        val loader = StartupLoader(scope) {
            creates++
            entered.complete(Unit)
            release.await()
            expected
        }
        try {
            val waiting = List(100) { async { loader.load() } }
            entered.await()
            release.complete(Unit)
            waiting.forEach { assertSame(expected, it.await()) }
            assertSame(expected, loader.load())
            assertEquals(1, creates)
        } finally {
            scope.cancel()
        }
    }

    @Test fun activityCancellationDoesNotAbortStorageInitializationOrStartADuplicate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var creates = 0
        val loader = StartupLoader(scope) {
            creates++
            entered.complete(Unit)
            release.await()
            "existing session"
        }
        try {
            val first = async { loader.load() }
            entered.await()
            first.cancelAndJoin()
            val second = async { loader.load() }
            release.complete(Unit)
            assertEquals("existing session", second.await())
            assertEquals(1, creates)
        } finally {
            scope.cancel()
        }
    }

    @Test fun failedInitializationCanRetryWithoutAResetOrNewIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val storedIdentity = Any()
        var attempts = 0
        val loader = StartupLoader(scope) {
            if (++attempts == 1) throw IllegalStateException("temporarily busy")
            storedIdentity
        }
        try {
            try {
                loader.load()
                fail("Expected failure")
            } catch (_: IllegalStateException) { }
            assertSame(storedIdentity, loader.load())
            assertSame(storedIdentity, loader.load())
            assertEquals(2, attempts)
        } finally {
            scope.cancel()
        }
    }
}
