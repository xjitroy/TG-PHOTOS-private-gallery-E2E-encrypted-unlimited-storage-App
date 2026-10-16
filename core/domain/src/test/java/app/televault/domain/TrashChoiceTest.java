package app.televault.domain;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static app.televault.domain.TrashChoice.Scope.*;

public class TrashChoiceTest {
    @Test public void deviceOnlyFilesHaveTwoChoicesAndNoCloudAction() {
        assertEquals(List.of(DEVICE,EVERYWHERE),TrashChoice.options(0));
        assertTrue(TrashChoice.enabled(DEVICE,4,0));assertFalse(TrashChoice.enabled(CLOUD,4,0));
    }
    @Test public void backedFilesOfferThreeAndMissingDeviceCannotEnableDeviceDeletion() {
        assertEquals(List.of(DEVICE,CLOUD,EVERYWHERE),TrashChoice.options(3));
        assertFalse(TrashChoice.enabled(DEVICE,0,3));assertTrue(TrashChoice.enabled(EVERYWHERE,0,3));
    }
    @Test public void canceledOrPartialDeviceTrashNeverTriggersEverywhereCloudAction() {
        assertFalse(TrashChoice.cloudAfterDevice(EVERYWHERE,3,0));assertFalse(TrashChoice.cloudAfterDevice(EVERYWHERE,3,2));
        assertTrue(TrashChoice.cloudAfterDevice(EVERYWHERE,3,3));assertTrue(TrashChoice.cloudAfterDevice(EVERYWHERE,0,0));
        assertFalse(TrashChoice.cloudAfterDevice(DEVICE,3,3));assertTrue(TrashChoice.cloudAfterDevice(CLOUD,0,0));
    }
}
