package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class VaultScopeTest {
    @Test public void workerCannotBorrowAnExistingAccountOrVaultKey() {
        assertTrue(VaultScope.workerMayUse(12,"a",12,"a"));
        assertFalse(VaultScope.workerMayUse(12,"a",13,"a"));
        assertFalse(VaultScope.workerMayUse(12,"a",12,"b"));
        assertFalse(VaultScope.workerMayUse(0,"",0,"a"));
        assertFalse(VaultScope.workerMayUse(0,"",12,""));
    }
    @Test public void initialBackgroundWorkCanEstablishItsScope() {
        assertTrue(VaultScope.workerMayUse(0,"",12,"a"));
        assertTrue(VaultScope.workerMayUse(12,"",12,"a"));
    }
    @Test public void discoveryMustPreserveAadWhileAnyWorkerHoldsAKey() {
        assertTrue(VaultScope.maySelect(12,"a",12,"a",2));
        assertFalse(VaultScope.maySelect(12,"a",13,"a",2));
        assertFalse(VaultScope.maySelect(12,"a",12,"b",1));
        assertFalse(VaultScope.maySelect(12,"a",12,"",1));
        assertTrue(VaultScope.maySelect(12,"a",13,"b",0));
        assertFalse(VaultScope.maySelect(12,"a",13,"b",-1));
    }
}
