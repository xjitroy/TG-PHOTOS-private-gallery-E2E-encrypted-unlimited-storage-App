package app.televault.data

import org.junit.Assert.*
import org.junit.Test

class ForgottenVaultTargetsTest {
    private val target = ForgottenVaultTicket("request-a", 42L, "photos-a", "private-a", 1000, "PENDING")

    @Test fun cancellationIncludesEveryConcurrentRequestForTheSameTarget() {
        val otherDevice = target.copy(id = "request-b", requestedAt = 2000)
        val started = target.copy(id = "request-c", phase = "STARTED")
        val cancelled = target.copy(id = "request-d", phase = "CANCELLED")
        assertEquals(listOf(target, otherDevice, started), pendingDeletionRequests(listOf(target, otherDevice, cancelled, started), target))
    }

    @Test fun aDifferentAccountPhotosOrPrivateVaultCannotBeCancelledByThisAction() {
        val others = listOf(target.copy(account = 43), target.copy(photos = "photos-b"), target.copy(privateVault = "private-b"), target.copy(privateVault = null), target.copy(phase = "DONE"))
        assertEquals(listOf(target), pendingDeletionRequests(others + target, target))
    }
}
