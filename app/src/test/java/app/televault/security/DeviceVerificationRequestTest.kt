package app.televault.security

import org.junit.Assert.*
import org.junit.Test

class DeviceVerificationRequestTest {
    @Test fun backgroundCancelsBiometricAndAllowsFreshRequest() {
        val request = DeviceVerificationRequest()
        var unlocked = false
        val old = request.begin { unlocked = true }!!
        assertTrue(request.background())
        assertNull(request.take(old))
        assertFalse(unlocked)
        val fresh = request.begin { unlocked = true }!!
        request.take(fresh)!!()
        assertTrue(unlocked)
    }

    @Test fun externalPinSurvivesBackgroundButDroppedResultCanBeRetried() {
        val request = DeviceVerificationRequest()
        val old = request.begin {}!!
        assertTrue(request.credential(old))
        assertFalse(request.background())
        assertEquals(old, request.credentialGeneration)
        assertTrue(request.resumedWithoutCredentialResult())
        assertNull(request.take(old))
        assertFalse(request.credentialInFlight)
        assertNotNull(request.begin {})
    }

    @Test fun credentialResultAndOemLateCallbackHaveExactlyOneWinner() {
        val request = DeviceVerificationRequest()
        var actions = 0
        val token = request.begin { actions++ }!!
        request.credential(token)
        request.take(token)!!()
        assertNull(request.take(token))
        assertFalse(request.resumedWithoutCredentialResult())
        assertEquals(1, actions)
    }

    @Test fun retryOrPasswordRevokesOldResultWithoutGrantingAccess() {
        val request = DeviceVerificationRequest()
        var actions = 0
        val old = request.begin { actions++ }!!
        assertNull(request.begin {})
        request.invalidate()
        val fresh = request.begin { actions++ }!!
        assertNull(request.take(old))
        assertFalse(request.credential(old))
        assertEquals(fresh, request.generation)
        assertEquals(0, actions)
        request.take(fresh)!!()
        assertEquals(1, actions)
    }
}
