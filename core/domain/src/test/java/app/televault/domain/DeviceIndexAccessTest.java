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
    @Test public void stablePartialCategoryAndSafGrantsCanRestoreExactStampedSnapshot() {
        assertTrue(DeviceIndexAccess.cachedSnapshotAllowed(DeviceIndexAccess.limitedVisual(false,true,false,false),true,3000));
        assertTrue(DeviceIndexAccess.cachedSnapshotAllowed(DeviceIndexAccess.limitedVisual(false,false,false,false),true,0));
    }
    @Test public void changedSelectedPhotoMembershipNeverRestoresCachedRows() {
        assertFalse(DeviceIndexAccess.cachedSnapshotAllowed(true,true,1000));
        assertFalse(DeviceIndexAccess.cachedSnapshotAllowed(false,false,1000));
    }
    @Test public void staleOrFutureClockSnapshotsMustRequeryProviders() {
        assertFalse(DeviceIndexAccess.cachedSnapshotAllowed(false,true,-1));
        assertFalse(DeviceIndexAccess.cachedSnapshotAllowed(false,true,86400000L));
        assertTrue(DeviceIndexAccess.cachedSnapshotAllowed(false,true,86399999L));
    }
}
