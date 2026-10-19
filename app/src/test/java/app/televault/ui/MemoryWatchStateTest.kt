package app.televault.ui
import org.junit.Assert.*
import org.junit.Test

class MemoryWatchStateTest {
    @Test fun openingLastPageOrRepeatedPagesDoesNotMarkWholeStoryWatched() {
        val watch = MemoryWatchState(setOf("a", "b", "c"))
        assertFalse(watch.viewed("c"))
        assertFalse(watch.viewed("c"))
        assertFalse(watch.viewed("wrong-story"))
        assertFalse(watch.viewed("a"))
        assertTrue(watch.viewed("b"))
        assertFalse(MemoryWatchState(emptySet()).viewed("a"))
    }

    @Test fun NewPhotoOrChangedSourceMakesTheCardUnseenAgain() {
        val a = listOf(Triple("photo", 100L, 200L))
        val before = memoryReceipt("recent", a)
        assertEquals(before, memoryReceipt("recent", a))
        assertNotEquals(before, memoryReceipt("other", a))
        assertNotEquals(before, memoryReceipt("recent", a + Triple("new", 100L, 200L)))
        assertNotEquals(before, memoryReceipt("recent", listOf(Triple("photo", 100L, 300L))))
    }
}
