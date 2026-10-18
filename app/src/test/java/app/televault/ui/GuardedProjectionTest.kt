package app.televault.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuardedProjectionTest {
    @Test fun lockedOcrOrRevokedPhotoCannotCrashCompositionOrExposeOldRows() = runBlocking {
        val actual = guardedProjection(emptyList<String>()) { throw IllegalStateException("Index not active") }
        assertTrue(actual.isEmpty())
    }

    @Test fun successfulProjectionIsKept() = runBlocking {
        assertEquals(listOf("current"), guardedProjection(emptyList<String>()) { listOf("current") })
    }

    @Test fun cancellationIsNeverConvertedIntoAResult() = runBlocking {
        try {
            guardedProjection(emptyList<String>()) { throw CancellationException("scope changed") }
            fail("must cancel")
        } catch (_: CancellationException) { }
    }

    @Test fun memoryPressureDropsOptionalProjection() = runBlocking {
        assertNull(guardedProjection<String?>(null) { throw OutOfMemoryError("decode") })
    }
}
