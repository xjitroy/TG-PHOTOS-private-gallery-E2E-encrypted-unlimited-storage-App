package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceBackupFolderTest {
    @Test public void selectedFolderIsExactAndDoesNotUploadAdjacentFolders() {
        assertTrue(DeviceBackupFolder.matches("DCIM/Camera/", "DCIM/Camera", false));
        assertFalse(DeviceBackupFolder.matches("DCIM/Camera/private", "DCIM/Camera", false));
        assertFalse(DeviceBackupFolder.matches("Pictures/Camera", "DCIM/Camera", false));
        assertFalse(DeviceBackupFolder.matches("DCIM/Camera2", "DCIM/Camera", false));
        assertTrue(DeviceBackupFolder.matches("DCIM/Camera", "", true));
        assertFalse(DeviceBackupFolder.matches("Pictures/CameraPrivate", "", true));
    }
    @Test public void invalidRelativeFoldersAreRejected() {
        for (String folder : new String[]{"", " ", "/DCIM/Camera", "DCIM/../private", "DCIM//Camera", "DCIM/./Camera"})
            assertThrows(IllegalArgumentException.class, () -> DeviceBackupFolder.normalize(folder));
        assertEquals("DCIM/Camera", DeviceBackupFolder.normalize("DCIM/Camera/"));
    }
}
