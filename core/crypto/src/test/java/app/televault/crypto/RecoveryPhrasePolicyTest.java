package app.televault.crypto;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class RecoveryPhrasePolicyTest {
    @Test public void acceptsOwnerSixDigitsAndExistingLongPhraseButRejectsOtherShortInputs() {
        for (String value : new String[]{"123456", "004207", "1234567", "12345678901", "original long recovery password"}) {
            assertTrue(RecoveryPhrasePolicy.accepts(value));
            assertTrue(RecoveryPhrasePolicy.accepts(value.toCharArray()));
        }
        for (String value : new String[]{"12345", "abcdef", "12a456", "", "x".repeat(513)}) {
            assertFalse(RecoveryPhrasePolicy.accepts(value));
            assertFalse(RecoveryPhrasePolicy.accepts(value.toCharArray()));
        }
        assertTrue(RecoveryPhrasePolicy.isSixDigit("004207"));
        assertFalse(RecoveryPhrasePolicy.isSixDigit("a long phrase"));
    }
    @Test public void sixDigitResetPreservesRandomMasterAndStillRejectsWrongPassword() throws Exception {
        byte[] master = VaultCrypto.randomKey(), original = master.clone(), aad = "account/vault/root".getBytes();
        char[] pin = "004207".toCharArray();
        RecoveryEnvelope.Changed reset = RecoveryEnvelope.rewrap(master, pin, aad);
        assertArrayEquals(original, master);
        assertArrayEquals(new char[pin.length], pin);
        assertTrue(RecoveryEnvelope.verify(master, "004207".toCharArray(), reset.salt(), reset.wrapped(), aad));
        assertFalse(RecoveryEnvelope.verify(master, "004208".toCharArray(), reset.salt(), reset.wrapped(), aad));
        assertThrows(IllegalArgumentException.class, () -> VaultCrypto.recoveryKey("12345".toCharArray(), reset.salt()));
        assertThrows(java.security.GeneralSecurityException.class, () -> RecoveryEnvelope.rewrap(new byte[16], "004207".toCharArray(), aad));
        assertFalse(Arrays.equals(master, VaultCrypto.recoveryKey("004207".toCharArray(), reset.salt())));
    }
}
