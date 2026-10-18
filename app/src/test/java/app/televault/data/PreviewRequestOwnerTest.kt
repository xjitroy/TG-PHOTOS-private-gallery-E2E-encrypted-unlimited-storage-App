package app.televault.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PreviewRequestOwnerTest {
    @Test fun cancellationClosesOnlyItsReadsAndRejectsLatePublish() {
        val first = PreviewRequestOwner()
        val second = PreviewRequestOwner()
        var firstCloses = 0
        var secondCloses = 0
        first.register(AutoCloseable { firstCloses++ })
        second.register(AutoCloseable { secondCloses++ })
        first.close()
        first.close()
        assertEquals(1, firstCloses)
        assertEquals(0, secondCloses)
        assertThrows(CancellationException::class.java) { first.publish { fail("Stale pixels") } }
        second.publish { assertEquals(0, secondCloses) }
        second.close()
        assertEquals(1, secondCloses)
    }

    @Test fun aReadOpenedAfterCancellationIsClosedInsteadOfLeaked() {
        val owner = PreviewRequestOwner()
        var closed = false
        owner.close()
        assertThrows(CancellationException::class.java) { owner.register(AutoCloseable { closed = true }) }
        assertTrue(closed)
    }
}
