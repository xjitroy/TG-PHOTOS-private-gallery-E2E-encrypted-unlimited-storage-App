package app.televault.data

import org.junit.Assert.*
import org.junit.Test

class BrowsePositionSafetyTest {
    @Test fun disablingLocationStartsAtPhotosWithoutAnOldFolderOrTarget() {
        val saved = BrowsePosition(tab = "LIBRARY", libraryPath = "Documents", index = 30, focusId = "file")
        assertEquals(BrowsePosition(), BrowseResumeOptions(location = false).restore(saved))
    }

    @Test fun disablingItemReturnStillRestoresTheNormalFolderAndScroll() {
        val saved = BrowsePosition(tab = "GALLERY", folder = "Camera", index = 40, focusId = "video")
        assertEquals(saved.copy(focusId = ""), BrowseResumeOptions(item = false).restore(saved))
        assertEquals(saved, BrowseResumeOptions().restore(saved))
        assertEquals(BrowsePosition(tab = "GALLERY", section = "Albums"), BrowseResumeOptions().restore(BrowsePosition(tab = "VAULT", focusId = "private")))
    }

    @Test fun privateVaultCannotPersistOrRestoreAnItemTarget() {
        val safe = BrowsePosition(tab = "VAULT", folder = "private", focusId = "private-item").safe()
        assertEquals("GALLERY", safe.tab)
        assertNull(safe.folder)
        assertEquals("", safe.focusId)
    }

    @Test fun malformedBookmarkCannotPersistUnboundedIdentifiers() {
        val safe = BrowsePosition(focusId = "x".repeat(10000), index = -3, offset = Int.MAX_VALUE).safe()
        assertEquals(4096, safe.focusId.length)
        assertEquals(0, safe.index)
        assertEquals(100000, safe.offset)
    }
}
