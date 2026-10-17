package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class ViewerOrientationLeaseTest {
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
