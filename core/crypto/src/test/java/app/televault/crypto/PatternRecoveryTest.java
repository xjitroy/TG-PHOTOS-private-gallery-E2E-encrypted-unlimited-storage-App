package app.televault.crypto;
import org.junit.Test;
import static org.junit.Assert.*;
public class PatternRecoveryTest {
    @Test public void canonicalDotsIncludeSkippedMidpointsAndNeverRepeat() {
        assertEquals("123",PatternRecovery.append("1",3));
        assertEquals("159",PatternRecovery.append("1",9));
        assertEquals("123",PatternRecovery.append("123",2));
        assertNull(PatternRecovery.decode("123456"));
        assertThrows(IllegalArgumentException.class,()->PatternRecovery.encode("1123"));
        assertThrows(IllegalArgumentException.class,()->PatternRecovery.encode("123"));
        assertEquals("1236",PatternRecovery.decode(PatternRecovery.encode("1236")));
    }
    @Test public void samePatternRecoversSameMasterWithoutDeviceKeyButDifferentPatternFails() throws Exception {
        byte[] master=VaultCrypto.randomKey(), aad="account/vault/root".getBytes();
        var root=RecoveryEnvelope.rewrap(master,PatternRecovery.encode("1236").toCharArray(),aad);
        assertTrue(RecoveryEnvelope.verify(master,PatternRecovery.encode("1236").toCharArray(),root.salt(),root.wrapped(),aad));
        assertFalse(RecoveryEnvelope.verify(master,PatternRecovery.encode("1239").toCharArray(),root.salt(),root.wrapped(),aad));
    }
}
