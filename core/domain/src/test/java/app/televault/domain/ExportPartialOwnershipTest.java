package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExportPartialOwnershipTest {
    private static final String ID = "12345678-1234-1234-1234-123456789012";
    private static final String OTHER = "12345678-1234-1234-1234-123456789013";
    private static final String URI = "content://local/tree/download/document/owned";
    @Test public void exactNewPartialCanBeRetiredAfterCancellation() {
        assertTrue(ExportPartialOwnership.mayDelete(ID, ID, URI, URI, "backup.partial.zip", "backup.partial.zip", "backup.zip", false));
    }
    @Test public void anotherExportOrDocumentNeverBecomesDeletionPermission() {
        assertFalse(ExportPartialOwnership.mayDelete(ID, OTHER, URI, URI, "backup.partial.zip", "backup.partial.zip", "backup.zip", false));
        assertFalse(ExportPartialOwnership.mayDelete(ID, ID, URI + "-other", URI, "backup.partial.zip", "backup.partial.zip", "backup.zip", false));
        assertFalse(ExportPartialOwnership.mayDelete(ID, "", URI, "", "backup.partial.zip", "", "backup.zip", false));
    }
    @Test public void publishedRenamedOrCompletedFilesStay() {
        assertFalse(ExportPartialOwnership.mayDelete(ID, ID, URI, URI, "backup.zip", "backup.partial.zip", "backup.zip", false));
        assertFalse(ExportPartialOwnership.mayDelete(ID, ID, URI, URI, "backup.zip", "backup.zip", "backup.zip", false));
        assertFalse(ExportPartialOwnership.mayDelete(ID, ID, URI, URI, "backup.partial.zip", "backup.partial.zip", "backup.zip", true));
        assertFalse(ExportPartialOwnership.mayDelete(ID, ID, URI, URI, "renamed-by-owner.zip", "backup.partial.zip", "backup.zip", false));
    }
    @Test public void providerChosenNewPartialNameRemainsExactlyBound() {
        assertTrue(ExportPartialOwnership.mayDelete(ID, ID, URI, URI, "backup.partial (1).zip", "backup.partial (1).zip", "backup.zip", false));
        assertFalse(ExportPartialOwnership.mayDelete("invalid", "invalid", URI, URI, "partial.zip", "partial.zip", "final.zip", false));
    }
}
