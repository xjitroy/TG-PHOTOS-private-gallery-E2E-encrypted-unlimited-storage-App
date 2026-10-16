package app.televault.domain;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalBackupReceiptsTest {
    private static final String SOURCE = "content://media/17|4|123456";
    private String digest(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(BackedCopyVerifier.digest(new ByteArrayInputStream(bytes), bytes.length, () -> {}));
    }

    @Test public void restoredSameUriSizeAndTimeMustReadChangedContentsInsteadOfSkipping() throws Exception {
        // The restored manifest contains SOURCE, the same filename, size and mtime.
        // Clear data removed device-local receipts: none can be restored from that manifest.
        Map<String, String> local = LocalBackupReceipts.retained(Set.of("cloud-photo"), Map.of());
        assertFalse(local.containsKey(SOURCE));
        assertFalse(BackupIdentity.same(digest("old!"), 4, digest("new!"), 4, true, false));
    }

    @Test public void unchangedRestoredOriginalGetsReceiptOnlyAfterFreshFullDigest() throws Exception {
        Map<String, String> receipts = new HashMap<>();
        assertFalse(LocalBackupReceipts.retained(Set.of("cloud-photo"), receipts).containsKey(SOURCE));
        assertTrue(BackupIdentity.same(digest("same"), 4, digest("same"), 4, true, false));
        receipts.put(SOURCE, "cloud-photo");
        assertEquals("cloud-photo", LocalBackupReceipts.retained(Set.of("cloud-photo"), receipts).get(SOURCE));
    }

    @Test public void purgedAndOrphanReceiptsCannotSuppressAnotherBackup() {
        Map<String, String> receipts = Map.of(SOURCE, "purged-photo", "another-source", "retained-photo");
        Map<String, String> selected = LocalBackupReceipts.retained(Set.of("retained-photo"), receipts);
        assertFalse(selected.containsKey(SOURCE));
        assertEquals(Map.of("another-source", "retained-photo"), selected);
        assertEquals(2, receipts.size());
    }

    @Test public void blankSourceCannotAuthorizeSkippingUnidentifiedFiles() {
        assertTrue(LocalBackupReceipts.retained(Set.of("cloud-photo"), Map.of("  ", "cloud-photo")).isEmpty());
    }
}
