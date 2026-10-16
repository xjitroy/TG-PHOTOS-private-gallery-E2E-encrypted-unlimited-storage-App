package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class VaultColdStartTest {
    @Test public void anOldQueuedWorkerCannotSelectItsVaultBeforeActivityRestoresThePersistedSelection() {
        assertFalse(VaultScope.workerMayUse(0,"",42,"old-vault","selected-vault"));
        assertTrue(VaultScope.workerMayUse(0,"",42,"selected-vault","selected-vault"));
        assertFalse(VaultScope.workerMayUse(42,"selected-vault",42,"old-vault","selected-vault"));
        assertFalse(VaultScope.workerMayUse(43,"selected-vault",42,"selected-vault","selected-vault"));
        assertTrue(VaultScope.workerMayUse(0,"",42,"restored-vault",""));
    }
}
