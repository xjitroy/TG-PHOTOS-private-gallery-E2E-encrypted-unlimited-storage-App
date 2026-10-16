package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceIndexAccessTest {
    @Test public void deniedAudioDoesNotEraseStableFullyGrantedPhotoVideoIndexOnRecents() {
        String photoVideoOnly="false|true,true,false|";
        assertFalse(DeviceIndexAccess.invalidate(photoVideoOnly,photoVideoOnly,DeviceIndexAccess.limitedVisual(false,true,true,false)));
    }
    @Test public void stableSingleCategoryGrantCanStayInRam() {
        String photosOnly="false|true,false,false|";
        assertFalse(DeviceIndexAccess.invalidate(photosOnly,photosOnly,DeviceIndexAccess.limitedVisual(false,true,false,false)));
    }
    @Test public void selectedPhotoScopeMustRequeryEvenWhenItsPermissionBitsAreUnchanged() {
        assertTrue(DeviceIndexAccess.invalidate("selected-grant","selected-grant",DeviceIndexAccess.limitedVisual(false,false,false,true)));
        assertTrue(DeviceIndexAccess.limitedVisual(false,true,false,true));
        assertFalse(DeviceIndexAccess.limitedVisual(false,true,true,true));
        assertFalse(DeviceIndexAccess.limitedVisual(true,false,false,true));
    }
    @Test public void revocationOfPermissionAllFilesOrFolderGrantAlwaysClearsCachedIndex() {
        for (String[] transition:new String[][]{{"images:on","images:off"},{"all-files:on","all-files:off"},{"folder:read","folder:revoked"}})
            assertTrue(DeviceIndexAccess.invalidate(transition[0],transition[1],false));
    }
    @Test public void noPriorSnapshotDoesNotAdvanceTheGenerationOnEveryRefresh() {
        assertFalse(DeviceIndexAccess.invalidate("","selected-grant",true));
    }
}
