package app.televault.domain;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class BackupCopyIndexTest {
    private static final String HASH = Base64.getEncoder().encodeToString(new byte[32]);
    private BackupCopyIndex.Copy copy(String id, String name, long size, String hash, boolean live) { return new BackupCopyIndex.Copy(id, name, size, hash, live); }
    @Test public void sameNameAndSizeDoNotProveTheOriginalIsBackedUp() {
        var index = new BackupCopyIndex(List.of(copy("cloud", "Camera.jpg", 12, HASH, true)));
        byte[] different = new byte[32];different[0] = 1;
        assertTrue(index.hasCandidates(12));
        assertNull(index.match("Camera.jpg", 12, Base64.getEncoder().encodeToString(different)));
        assertNull(index.match("Camera.jpg", 13, HASH));
    }
    @Test public void renamedAndRestoredUriCopiesMatchTheirExactBytes() {
        var index = new BackupCopyIndex(List.of(copy("cloud", "old-name.mp4", 123, HASH, true)));
        assertEquals("cloud", index.match("new-name.mp4", 123, HASH).id());
    }
    @Test public void trashedPurgedAndInvalidHashesAreNotReusable() {
        var index = new BackupCopyIndex(List.of(copy("trash", "a", 12, HASH, false), copy("invalid", "b", 13, "bad-hash", true), copy("negative", "c", -1, HASH, true)));
        assertFalse(index.hasCandidates(12));
        assertFalse(index.hasCandidates(13));
        assertFalse(index.hasCandidates(-1));
    }
    @Test public void matchingNameOnlyBreaksTiesBetweenVerifiedIdenticalCopies() {
        var index = new BackupCopyIndex(List.of(copy("first", "other.jpg", 12, HASH, true), copy("named", "camera.JPG", 12, HASH, true)));
        assertEquals("named", index.match("Camera.jpg", 12, HASH).id());
        assertNull(index.match("Camera.jpg", 12, null));
        assertNull(index.match("Camera.jpg", 12, "not-a-hash"));
    }
    @Test public void incrementalLookupDoesNotNeedOriginalCloudMedia() {
        var copies = new ArrayList<BackupCopyIndex.Copy>();
        for (int i = 0; i < 10000; i++) copies.add(copy("cloud" + i, "file" + i, i, HASH, true));
        var index = new BackupCopyIndex(copies);
        for (int i = 0; i < 10000; i++) assertEquals("cloud" + i, index.match("renamed", i, HASH).id());
    }
}
