package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class AlbumLayoutTest {
    @Test fun primaryAlbumsUseActualDeviceFoldersWithoutInventingCategories() {
        assertTrue(primaryDeviceAlbum("device:all", null, "All", true))
        assertTrue(primaryDeviceAlbum("device:favorites", null, "Favorites", false))
        assertTrue(primaryDeviceAlbum("device:videos", null, "Videos", false))
        assertTrue(primaryDeviceAlbum("device:x", "DCIM/Camera", "All", false))
        assertTrue(primaryDeviceAlbum("device:y", "Pictures/Screenshots", "All", false))
        assertTrue(primaryDeviceAlbum("device:z", "Movies/Screen recordings", "All", false))
    }

    @Test fun ordinaryFoldersStayInMoreAlbumsAndCloudNamesDoNotImpersonateAll() {
        assertFalse(primaryDeviceAlbum("cloud:all", null, "All", true))
        assertFalse(primaryDeviceAlbum("device:a", "Download", "All", false))
        assertFalse(primaryDeviceAlbum("device:b", "Pictures/Instagram", "All", false))
    }
}
