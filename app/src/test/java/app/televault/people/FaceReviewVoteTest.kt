package app.televault.people

import org.junit.Assert.*
import org.junit.Test

class FaceReviewVoteTest {
    @Test fun singleMistakenDifferentDoesNotCommit() {
        val vote = FaceReviewVote().next("person", false)
        assertFalse(vote.confirmed)
        assertEquals(1, vote.count)
    }

    @Test fun twoMatchingSameReviewsConfirmOnlyThatPair() {
        assertTrue(FaceReviewVote().next("person", true).next("person", true).confirmed)
    }

    @Test fun twoMatchingDifferentReviewsAreAlsoRequired() {
        assertTrue(FaceReviewVote().next("person", false).next("person", false).confirmed)
    }

    @Test fun contradictoryReviewsRemainPendingAfterThreeOrMoreAttempts() {
        var vote = FaceReviewVote()
        repeat(8) {
            vote = vote.next("person", it % 2 == 0)
            assertFalse(vote.confirmed)
        }
        assertTrue(vote.next("person", vote.same).confirmed)
    }

    @Test fun newPairCannotReuseConfirmationForAnotherPerson() {
        val vote = FaceReviewVote().next("first", true).next("second", true)
        assertFalse(vote.confirmed)
        assertEquals("second", vote.target)
    }
}
