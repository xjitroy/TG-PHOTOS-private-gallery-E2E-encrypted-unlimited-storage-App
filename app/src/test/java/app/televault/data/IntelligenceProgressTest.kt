package app.televault.data

import org.junit.Assert.*
import org.junit.Test

class IntelligenceProgressTest {
    @Test fun attemptsAndOneCompleteEngineCannotReportAllComplete() {
        var latest = IntelligenceProgress()
        val tracker = IntelligenceProgressTracker { latest = it }
        tracker.begin(setOf("face", "text"))
        tracker.candidates(setOf("a" to "1", "b" to "2"))
        assertEquals(0, latest.percent)
        tracker.record("a", "1", setOf("text"))
        tracker.record("b", "2", setOf("text"))
        assertEquals(50, latest.percent)
        tracker.phase("attention")
        assertEquals(50, latest.percent)
    }

    @Test fun reuseIsCountedOnceAndRemovedOrChangedSourcesDoNotCount() {
        var latest = IntelligenceProgress()
        val tracker = IntelligenceProgressTracker { latest = it }
        tracker.begin(setOf("text"))
        tracker.candidates(setOf("a" to "1"))
        repeat(3) { tracker.record("a", "1", setOf("text")) }
        assertEquals(100, latest.percent)
        tracker.candidates(setOf("a" to "2", "b" to "1"))
        assertEquals(0, latest.percent)
    }

    @Test fun unknownTotalsAndDisabledFeaturesAreNotFakeCompletion() {
        assertNull(IntelligenceProgress().percent)
        assertNull(IntelligenceProgress("preparing", enabled = setOf("text")).percent)
        assertNull(IntelligenceProgress(total = 10, enabled = setOf("face")).percent("text"))
        assertEquals(100, IntelligenceProgress("complete", enabled = setOf("text")).percent)
    }

    @Test fun privacyNeedsSuccessfulEnumerationAndAuthenticationEvenAtLastRecord() {
        assertNull(PrivacyRestoreProgress("listing").percent)
        assertEquals(99, PrivacyRestoreProgress("restoring", 1, 1).percent)
        assertEquals(99, PrivacyRestoreProgress("blocked", 1, 1, "authentication").percent)
        assertEquals(100, PrivacyRestoreProgress("ready", 1, 1).percent)
        assertEquals(100, PrivacyRestoreProgress("ready").percent)
    }

    @Test fun sameScopeRetryKeepsSavedResultsAndNewModelScopeStartsFresh() {
        var latest = IntelligenceProgress()
        val tracker = IntelligenceProgressTracker { latest = it }
        tracker.begin(setOf("text"), "account/vault/epoch/model")
        tracker.candidates(setOf("a" to "1", "b" to "2"))
        tracker.record("a", "1", setOf("text"))
        tracker.begin(setOf("text"), "account/vault/epoch/model")
        assertEquals(50, latest.percent)
        tracker.begin(setOf("text"), "account/vault/epoch/replacement")
        tracker.candidates(setOf("a" to "1", "b" to "2"))
        assertEquals(0, latest.percent)
    }

    @Test fun lockClearsPriorScopedCountsAndDisabledEngineDoesNotAffectOverall() {
        var latest = IntelligenceProgress()
        val tracker = IntelligenceProgressTracker { latest = it }
        tracker.begin(setOf("text", "face"), "scope")
        tracker.candidates(setOf("a" to "1"))
        tracker.record("a", "1", setOf("text"))
        tracker.begin(setOf("text"), "scope")
        assertEquals(100, latest.percent)
        tracker.begin(emptySet())
        assertNull(latest.percent)
        assertEquals(0, latest.total)
    }

    @Test fun hashVerifiedPrivateSourceIsRemovedFromAllEligibleTotals() {
        var latest = IntelligenceProgress()
        val tracker = IntelligenceProgressTracker { latest = it }
        tracker.begin(setOf("text"), "scope")
        val sources = setOf("normal" to "1", "private" to "1")
        tracker.candidates(sources)
        tracker.record("normal", "1", setOf("text"))
        assertEquals(50, latest.percent)
        tracker.exclude("private", "1")
        assertEquals(1, latest.total)
        assertEquals(100, latest.percent)
        tracker.candidates(sources)
        assertEquals(1, latest.total)
        assertEquals(100, latest.percent)
    }
}
