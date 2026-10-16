package app.televault.ui

import app.televault.data.DriveFile
import app.televault.domain.MediaCategories.Category
import app.televault.telegram.RemoteDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class LibraryProjectionTest {
    private fun file(id: String, album: String, name: String = "Screenshot_$id.png", bytes: Long = 100, date: Long = 100) = DriveFile(id, name, if (name.endsWith(".png")) "image/png" else "video/mp4", bytes, date, album, "hash-$id", RemoteDocument(1, 2, 100), "", "token-$id").let { MediaEntry(it.id, it.name, it.mime, it.size, it.date, cloud = it) }
    private fun scope(folder: String = "Folders/Trip", filter: MediaFilterOptions = MediaFilterOptions(), sort: String = "Newest") = LibraryProjectionScope(10, true, folder, "All files", false, "", sort, filter)

    @Test fun categoryAndSearchCannotEscapeCurrentFolderOrMatchSiblingPrefix() = runBlocking {
        val files = listOf(file("own", "Trip"), file("nested", "Trip/Day2"), file("sibling", "Trip2"), file("other", "Other"), file("video", "Trip", "movie.mp4"))
        val request = scope(filter = MediaFilterOptions(Category.SCREENSHOTS)).copy(search = "shot")
        val result = projectLibrary(LibraryProjectionInput(files, listOf("Trip", "Trip/Day2", "Trip2", "Other")), request)
        assertEquals(setOf("own", "nested"), result.direct.map { it.file.id }.toSet())
        assertEquals(2, result.counts["Photos"])
        assertTrue(result.directories.isEmpty())
    }

    @Test fun folderTotalsSaturateAndSortFromAggregatesWithoutLosingFiles() = runBlocking {
        val files = listOf(file("a", "Trip", bytes = Long.MAX_VALUE - 10), file("b", "Trip", bytes = 100), file("c", "Other", bytes = Long.MAX_VALUE - 1))
        val result = projectLibrary(LibraryProjectionInput(files, listOf("Trip", "Other")), scope(folder = "Folders", sort = "Size"))
        assertEquals(listOf("Folders/Trip", "Folders/Other"), result.directories.map { it.key })
        assertEquals(setOf("a", "b"), result.directories.first().value.map { it.id }.toSet())
        assertEquals(3, result.filteredFiles.size)
    }

    @Test fun paintedFileMustStillMeetCurrentFolderAndCategoryBeforeAnAction() {
        val request = scope(filter = MediaFilterOptions(Category.SCREENSHOTS))
        val folders = listOf("Trip", "Other")
        assertTrue(request.allows(file("id", "Trip"), folders))
        assertFalse(request.allows(file("id", "Other"), folders))
        assertFalse(request.allows(file("id", "Trip", "holiday.mp4"), folders))
        val trashed = file("id", "Trip").let { it.copy(cloud = it.cloud!!.copy(trashedAt = 1)) }
        assertFalse(request.allows(trashed, folders))
    }

    @Test fun replacedProjectionStopsWalkingAnObsoleteLargeLibrary() = runBlocking {
        val owner = Job()
        var reads = 0
        val sample = file("id", "Trip")
        val files = object : AbstractList<MediaEntry>() {
            override val size = 10000
            override fun get(index: Int): MediaEntry {
                reads++
                if (index == 128) owner.cancel()
                return sample
            }
        }
        var cancelled = false
        try {
            withContext(owner) { projectLibrary(LibraryProjectionInput(files, listOf("Trip")), scope()) }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue("Cancellation must stop the obsolete scan early", reads in 129..257)
    }

    @Test fun retainedRowsAreIsolatedByAccessEpochFolderAndFilters() {
        val original = scope()
        val snapshot = ProjectionSnapshot(original, Any(), listOf("private-file"))
        assertEquals(listOf("private-file"), snapshot.valueIn(original))
        assertNull(snapshot.valueIn(original.copy(epoch = 11)))
        assertNull(snapshot.valueIn(original.copy(folder = "Folders/Other")))
        assertNull(snapshot.valueIn(original.copy(filter = MediaFilterOptions(clean = true))))
        assertNull(snapshot.valueIn(original.copy(cloud = false)))
    }
}
