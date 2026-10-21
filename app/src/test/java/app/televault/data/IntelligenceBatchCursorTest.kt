package app.televault.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IntelligenceBatchCursorTest {
    @Test fun boundedRetriesEventuallyReachTheTailDespiteSlowEarlyItems() {
        val cursor = IntelligenceBatchCursor()
        cursor.begin("account/vault/epoch")
        val files = (1..12).map { "photo-$it" }
        val seen = mutableSetOf<String>()
        repeat(6) {
            for (id in cursor.order(true, files) { it to "stamp" }.take(2)) {
                cursor.mark(true, id, "stamp")
                seen += id
            }
        }
        assertEquals(files.toSet(), seen)
    }

    @Test fun specialistAndLargeModelPositionsDoNotOverwriteEachOtherAndNewSessionResets() {
        val cursor = IntelligenceBatchCursor()
        cursor.begin("vault1/1")
        val files = listOf("a", "b", "c")
        cursor.mark(false, "b", "1")
        cursor.mark(true, "a", "1")
        assertEquals(listOf("c", "a", "b"), cursor.order(false, files) { it to "1" })
        assertEquals(listOf("b", "c", "a"), cursor.order(true, files) { it to "1" })
        cursor.begin("vault2/1")
        assertEquals(files, cursor.order(true, files) { it to "1" })
    }

    @Test fun disappearedOrReplacedResumeItemCannotHideNewFiles() {
        val cursor = IntelligenceBatchCursor()
        cursor.begin("vault/1")
        cursor.mark(false, "old", "1")
        assertEquals(listOf("new", "old"), cursor.order(false, listOf("new", "old")) { it to "2" })
    }

    @Test fun oneUnreadableSourceDoesNotStarveLaterValidPhotos() = runBlocking {
        val saved = mutableListOf<Int>()
        for (i in 1..4) {
            runIntelligenceItem {
                if (i == 1)throw java.io.IOException("source gone")
                saved += i
            }
        }
        assertEquals(listOf(2, 3, 4), saved)
    }

    @Test fun cancellationIsNeverMisreportedAsAnUnreadablePhoto() = runBlocking {
        try {
            runIntelligenceItem { throw CancellationException("locked") }
            fail()
        } catch (_: CancellationException) { }
    }
}
