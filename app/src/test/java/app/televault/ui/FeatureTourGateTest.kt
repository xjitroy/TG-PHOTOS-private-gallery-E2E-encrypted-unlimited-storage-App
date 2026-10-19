package app.televault.ui
import org.junit.Assert.*
import org.junit.Test

class FeatureTourGateTest {
    @Test fun nextRequiresOneRealSecondForEveryCard() {
        val gate = FeatureTourGate()
        gate.show(0, 100)
        assertFalse(gate.take(0, 1099))
        assertTrue(gate.take(0, 1100))
        gate.show(1, 1100)
        assertFalse(gate.take(1, 1101))
        assertTrue(gate.take(1, 2100))
    }

    @Test fun rapidDoubleClickCannotSkipAnotherCard() {
        val gate = FeatureTourGate()
        gate.show(0, 0)
        assertTrue(gate.take(0, 1000))
        assertFalse(gate.take(0, 1001))
        assertFalse(gate.take(1, 1001))
    }

    @Test fun backgroundTimeDoesNotCompleteAHiddenCard() {
        val gate = FeatureTourGate()
        gate.show(0, 0)
        gate.pause()
        assertFalse(gate.take(0, 100000))
        gate.show(0, 100000)
        assertFalse(gate.take(0, 100999))
        assertTrue(gate.take(0, 101000))
    }

    @Test fun lateOldCardCallbacksAndClockRollbackAreIgnored() {
        val gate = FeatureTourGate()
        gate.show(2, 5000)
        assertFalse(gate.take(1, 8000))
        assertFalse(gate.take(2, 4000))
        assertTrue(gate.take(2, 6000))
    }
}
