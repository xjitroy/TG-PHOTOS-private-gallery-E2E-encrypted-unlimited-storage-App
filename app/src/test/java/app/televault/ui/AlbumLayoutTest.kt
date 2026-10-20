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
    private fun folder(id: String, count: Int, date: Long) = AlbumTile(id, "media", List(count) { index -> MediaEntry("$id/$index", "Photo", "image/jpeg", 1, date) }, folder = id)

    @Test fun previewRanksMostItemsBeforeRecencyAndCapsAtSix() {
        val albums = (1..20).map { folder("folder-$it", it, 100L - it) }.reversed()
        val ranked = rankMoreAlbums(albums)
        assertEquals((20 downTo 1).map { "folder-$it" }, ranked.map { it.id })
        assertEquals((20 downTo 15).map { "folder-$it" }, moreAlbumPreview(ranked).map { it.id })
        assertEquals(20, ranked.size)
    }

    @Test fun equalCountsUseNewestThenIdentityWithNoFolderNameDeduplication() {
        val ranked = rankMoreAlbums(listOf(folder("storage-b/media", 2, 20), folder("storage-c/media", 2, 30), folder("storage-a/media", 2, 20)))
        assertEquals(listOf("storage-c/media", "storage-a/media", "storage-b/media"), ranked.map { it.id })
        assertEquals(3, moreAlbumPreview(ranked).size)
    }

    @Test fun emptyAndSmallLibrariesKeepEveryAvailableFolder() {
        assertTrue(moreAlbumPreview(rankMoreAlbums(emptyList())).isEmpty())
        val albums = listOf(folder("empty", 0, 0), folder("one", 1, 1))
        assertEquals(listOf("one", "empty"), moreAlbumPreview(rankMoreAlbums(albums)).map { it.id })
    }
}
