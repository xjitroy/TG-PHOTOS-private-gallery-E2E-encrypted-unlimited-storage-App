package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class ExternalFallbackGateTest {
    @Test fun backgroundFailureWaitsForForegroundAndOnlyOffersOnce() {
        val gate = ExternalFallbackGate()
        assertFalse(gate.claim(true, false))
        assertTrue(gate.claim(true, true))
        repeat(1000) { assertFalse(gate.claim(true, true)) }
    }

    @Test fun networkOrUnclassifiedFailureNeverClaimsTheChooser() {
        val gate = ExternalFallbackGate()
        assertFalse(gate.claim(false, true))
        assertTrue(gate.claim(true, true))
    }

    @Test fun aNewViewerEntryHasItsOwnOfferWithoutResettingTheOldOne() {
        val first = ExternalFallbackGate()
        assertTrue(first.claim(true, true))
        assertTrue(ExternalFallbackGate().claim(true, true))
        assertFalse(first.claim(true, true))
    }
}
