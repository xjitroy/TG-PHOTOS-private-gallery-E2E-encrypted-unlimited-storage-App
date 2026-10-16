package app.televault.ui

import app.televault.data.DriveFile
import app.televault.data.MediaFacts
import app.televault.domain.MediaBackupPolicy
import app.televault.domain.MediaCategories.Category
import app.televault.telegram.RemoteDocument
import org.junit.Assert.*
import org.junit.Test

class MediaFilterOptionsTest {
    private fun file(name: String, mime: String = "image/jpeg", facts: MediaFacts = MediaFacts()) = DriveFile("file-id", name, mime, 1000, 100, "Uploads", "original-hash", RemoteDocument(1, 2, 1000), "", "source-token", facts = facts)
    private fun entry(file: DriveFile) = MediaEntry(file.id, file.name, file.mime, file.size, file.date, cloud = file)

    @Test fun existingCloudNamesAreCategorizedWithoutDeviceFileOrMigration() {
        val screenshot = entry(file("Screenshot_Instagram_20261015.png", "image/png"))
        assertTrue(MediaFilterOptions(Category.SCREENSHOTS).allows(screenshot))
        assertTrue(MediaFilterOptions(Category.INSTAGRAM).allows(screenshot))
        assertFalse(MediaFilterOptions(Category.CAMERA).allows(screenshot))
        assertFalse(MediaFilterOptions(hidden = Category.PNG.mask).allows(screenshot))
    }

    @Test fun cleanAndHiddenOptionsNeverMutateFileOrAutomaticBackupEligibility() {
        val source = file("Screenshot_20261015.png", "image/png")
        val immutable = source.copy()
        val item = entry(source)
        assertFalse(MediaFilterOptions(clean = true).allows(item))
        assertTrue(MediaBackupPolicy.automaticAllowed(false, false, source.mime, source.date, 0))
        assertEquals(immutable, source)
        assertTrue(MediaFilterOptions().allows(item))
    }

    @Test fun cameraFactsAndDocumentScansStayVisibleAfterCloudFolderChanges() {
        val camera = file("Holiday.jpg", facts = MediaFacts(cameraMake = "Camera manufacturer"))
        val scan = file("Document_scan.png", "image/png")
        assertTrue(MediaFilterOptions(clean = true).allows(entry(camera.copy(album = "New folder"))))
        assertTrue(MediaFilterOptions(clean = true).allows(entry(scan)))
        assertFalse(MediaFilterOptions(hidden = Category.DOCUMENTS.mask, clean = true).allows(entry(scan)))
    }
}
