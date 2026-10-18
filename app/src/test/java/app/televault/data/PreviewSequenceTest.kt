package app.televault.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewSequenceTest {
    @Test fun thousandReplacementsLoadLatestSelectionBeforeItsNeighbors() = runBlocking {
        val loaded = mutableListOf<Int>()
        val sequence = PreviewSequence<Int>(this) { id, owner -> owner.publish { loaded += id } }
        repeat(1000) { sequence.request(listOf(it, it + 1, it - 1)) }
        yield()
        assertEquals(listOf(999, 1000, 998), loaded)
        sequence.close()
    }

    @Test fun newSelectionClosesOldReadsBeforeCancellingTheWorker() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val loaded = mutableListOf<Int>()
        val sequence = PreviewSequence<Int>(this) { id, owner ->
            if (id == 1) {
                owner.register(AutoCloseable { closed.complete(Unit) })
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                owner.publish { fail("Old pixels must not publish") }
            } else {
                owner.publish { loaded += id }
            }
        }
        sequence.request(listOf(1, 2))
        entered.await()
        sequence.request(listOf(5, 6))
        closed.await()
        yield()
        assertEquals(listOf(5, 6), loaded)
        release.complete(Unit)
        sequence.close()
    }

    @Test fun failureSkipsOneFileAndDisposalStopsWaitingNeighbors() = runBlocking {
        val loaded = mutableListOf<Int>()
        val sequence = PreviewSequence<Int>(this) { id, _ -> if (id == 1) error("Unreadable source") else loaded += id }
        sequence.request(listOf(1, 2))
        yield()
        assertEquals(listOf(2), loaded)
        sequence.request(listOf(3, 4))
        sequence.close()
        yield()
        assertEquals(listOf(2), loaded)
    }
}
