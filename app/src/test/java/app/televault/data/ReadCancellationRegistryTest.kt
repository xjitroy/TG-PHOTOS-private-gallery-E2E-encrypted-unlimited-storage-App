package app.televault.data

import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReadCancellationRegistryTest {
    @Test fun closingViewerCancelsEveryDescriptorButNotAnotherViewer() {
        val registry = ReadCancellationRegistry()
        val first = registry.open("photo")
        val other = registry.open("photo")
        val exif = registry.reader(first.token, "photo")
        val decoder = registry.reader(first.token, "photo")
        val independent = registry.reader(other.token, "photo")
        first.close()
        assertTrue(exif.isCancelled)
        assertTrue(decoder.isCancelled)
        assertTrue(independent.isActive)
        other.close()
        assertEquals(0, registry.size)
    }

    @Test fun oneDescriptorClosingDoesNotCancelTheViewerOrItsSibling() {
        val registry = ReadCancellationRegistry()
        val owner = registry.open("photo")
        val first = registry.reader(owner.token, "photo")
        val second = registry.reader(owner.token, "photo")
        first.cancel()
        assertTrue(second.isActive)
        val third = registry.reader(owner.token, "photo")
        assertTrue(third.isActive)
        owner.close()
        assertTrue(third.isCancelled)
    }

    @Test(expected = FileNotFoundException::class)
    fun anotherSourceCannotBorrowOwnership() {
        val registry = ReadCancellationRegistry()
        val owner = registry.open("photo")
        try {
            registry.reader(owner.token, "another-photo")
        } finally {
            owner.close()
        }
    }

    @Test(expected = FileNotFoundException::class)
    fun aLateOpenCannotReviveAClosedViewer() {
        val registry = ReadCancellationRegistry()
        val owner = registry.open("photo")
        owner.close()
        registry.reader(owner.token, "photo")
    }

    @Test fun nestedArtworkStopsWithItsParentAndDoesNotLeakItsRegistration() {
        val registry = ReadCancellationRegistry()
        val preview = registry.open("vault-photo")
        val connector = registry.reader(preview.token, "vault-photo")
        val artwork = registry.open("vault-photo", connector)
        val read = registry.reader(artwork.token, "vault-photo")
        preview.close()
        assertTrue(read.isCancelled)
        assertEquals(0, registry.size)
        artwork.close()
    }

    @Test fun cancellingAViewerInterruptsAColdHeaderReadWithoutWaitingForIt() {
        val registry = ReadCancellationRegistry()
        val owner = registry.open("cloud-photo")
        val job = registry.reader(owner.token, "cloud-photo")
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val reader = Thread {
            try {
                runBlocking(job + Dispatchers.IO) {
                    entered.countDown()
                    awaitCancellation()
                }
            } catch (_: CancellationException) {
                // A provider translates cancellation to a file-read error.
            } finally {
                exited.countDown()
            }
        }
        reader.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            owner.close()
            assertTrue(exited.await(3, TimeUnit.SECONDS))
        } finally {
            owner.close()
            reader.join(3000)
        }
        assertFalse(reader.isAlive)
    }

    @Test fun closeRacingWithChildCreationNeverLeavesAnActiveRead() {
        repeat(100) {
            val registry = ReadCancellationRegistry()
            val owner = registry.open("photo")
            val gate = CountDownLatch(1)
            var read: Job? = null
            val worker = Thread {
                gate.await()
                try {
                    read = registry.reader(owner.token, "photo")
                } catch (_: FileNotFoundException) { }
            }
            worker.start()
            gate.countDown()
            owner.close()
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertTrue(read == null || read!!.isCancelled)
            assertEquals(0, registry.size)
        }
    }

    @Test fun oldDisposalCannotCancelAReopenedPhoto() {
        val registry = ReadCancellationRegistry()
        val old = registry.open("photo")
        old.close()
        val replacement = registry.open("photo")
        val reader = registry.reader(replacement.token, "photo")
        old.close()
        assertTrue(reader.isActive)
        replacement.close()
        assertTrue(reader.isCancelled)
    }
}
