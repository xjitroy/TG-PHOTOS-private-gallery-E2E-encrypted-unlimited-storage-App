package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class LocalFolderOrderTest {
    @Test public void recentlyModifiedOldPhotosBringTheirFolderForward() {
        assertEquals(200000,LocalFolderOrder.updatedAt(100000,200));
        assertEquals(300000,LocalFolderOrder.updatedAt(300000,200));
        assertEquals(0,LocalFolderOrder.updatedAt(-1,-1));
        assertEquals(Long.MAX_VALUE,LocalFolderOrder.updatedAt(1,Long.MAX_VALUE));
    }
}
