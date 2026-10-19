package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class ViewerOrientationLeaseTest {
    @Test fun thumbnailScrubbingPausesOwnershipWithoutRotatingThenResolvesTheSettledKind() {
        var current = -1
        val changes = mutableListOf<Int>()
        val lease = ViewerOrientationLease(-1, 1, 0, { current }, {
            current = it
            changes.add(it)
        })
        lease.select("movie", true)
        lease.suspendSelection()
        lease.request("movie", false, false)
        assertEquals(0, current)
        lease.select("next-movie", true)
        assertEquals(listOf(0), changes)
        lease.suspendSelection()
        lease.select(null)
        assertEquals(1, current)
        lease.close()
        assertEquals(-1, current)
    }

    @Test fun switchingBetweenWideFullscreenVideosNeverRequestsPortraitInBetween() {
        var current = -1
        val changes = mutableListOf<Int>()
        val lease = ViewerOrientationLease(-1, 1, 0, { current }, {
            current = it
            changes.add(it)
        })
        lease.select("first", true)
        lease.request("first", true, false)
        lease.select("second", true)
        lease.request("first", false, false)
        lease.request("second", true, false)
        assertEquals(listOf(0), changes)
        lease.select("photo")
        assertEquals(1, current)
    }

    @Test fun outgoingPageCannotRotateNewPortraitPage() {
        var current = -1
        val lease = ViewerOrientationLease(-1, 1, 0, { current }, { current = it })
        lease.select("movie")
        lease.request("movie", true, false)
        assertEquals(0, current)
        lease.select("reel")
        lease.request("movie", true, false)
        assertEquals(1, current)
        lease.request("reel", false, false)
        assertEquals(1, current)
    }

    @Test fun onlyViewerExitRestoresOriginalAndOnlyOnce() {
        var current = -1
        var changes = 0
        val lease = ViewerOrientationLease(-1, 1, 0, { current }, {
            current = it
            changes++
        })
        lease.select("first")
        lease.select("second")
        lease.close()
        val count = changes
        lease.close()
        lease.request("second", true, false)
        assertEquals(-1, current)
        assertEquals(count, changes)
    }

    @Test fun PhotosAndPipDoNotInheritMovieLandscape() {
        var current = -1
        val lease = ViewerOrientationLease(-1, 1, 0, { current }, { current = it })
        lease.select("movie")
        lease.request("movie", true, false)
        lease.select(null)
        lease.request("movie", true, false)
        assertEquals(1, current)
        lease.select("reel")
        lease.request("reel", false, true)
        assertEquals(-1, current)
    }
}
