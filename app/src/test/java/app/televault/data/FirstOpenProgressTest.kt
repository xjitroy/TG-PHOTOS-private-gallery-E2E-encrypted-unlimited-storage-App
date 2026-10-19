package app.televault.data
import org.junit.Assert.*
import org.junit.Test

class FirstOpenProgressTest {
    @Test fun freshInstallShowsFeaturesBeforeTheWarningAndLegacyAcknowledgementDoesNotForceATour() {
        assertEquals(FirstOpenProgress(false, false), FirstOpenProgress.resolve(null, false))
        assertEquals(FirstOpenProgress(true, true), FirstOpenProgress.resolve(null, true))
        assertEquals(FirstOpenProgress(true, false), FirstOpenProgress.resolve(true, false))
    }

    @Test fun explicitReplayPreservesTheAlreadyAcknowledgedStorageWarning() {
        assertEquals(FirstOpenProgress(false, true), FirstOpenProgress.resolve(false, true))
        assertEquals(FirstOpenProgress(true, true), FirstOpenProgress.resolve(true, true))
    }
}
