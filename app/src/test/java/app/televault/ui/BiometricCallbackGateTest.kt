package app.televault.ui
import org.junit.Assert.*
import org.junit.Test
class BiometricCallbackGateTest {
    @Test fun successCannotBeUndoneByALateOemError() {
        val gate = BiometricCallbackGate()
        assertTrue(gate.terminal())
        assertFalse(gate.terminal())
        gate.dispose()
        assertFalse(gate.terminal())
    }

    @Test fun disposalRejectsEveryLateResult() {
        val gate = BiometricCallbackGate()
        gate.dispose()
        assertFalse(gate.terminal())
    }

    @Test fun competingCallbacksHaveExactlyOneWinner() {
        val gate = BiometricCallbackGate()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..20).map { Thread { if (gate.terminal())count.incrementAndGet() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, count.get())
    }
}
