package app.televault.crypto;

import org.junit.Test;
import static org.junit.Assert.*;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public class RecoveryEnvelopeTest {
    @Test public void changePreservesMasterAndRejectsOldPhraseInNewEnvelope() throws Exception {
        byte[] master = VaultCrypto.randomKey(), salt = VaultCrypto.randomBytes(16), aad = "account/vault/root".getBytes();
        byte[] wrapped = VaultCrypto.seal(VaultCrypto.recoveryKey("original recovery password".toCharArray(), salt), master, aad);
        char[] current = "original recovery password".toCharArray(), next = "new independent recovery phrase".toCharArray();
        RecoveryEnvelope.Changed changed = RecoveryEnvelope.change(master, current, salt, wrapped, next, aad);
        assertFalse(Arrays.equals(salt, changed.salt()));
        assertArrayEquals(master, VaultCrypto.open(VaultCrypto.recoveryKey("new independent recovery phrase".toCharArray(), changed.salt()), changed.wrapped(), aad));
        assertThrows(GeneralSecurityException.class, () -> VaultCrypto.open(VaultCrypto.recoveryKey("original recovery password".toCharArray(), changed.salt()), changed.wrapped(), aad));
        assertArrayEquals(new char[current.length], current);
        assertArrayEquals(new char[next.length], next);
    }
    @Test public void sensitiveActionRequiresCorrectPhraseVaultAndAuthenticatedEnvelope() throws Exception {
        byte[] master = VaultCrypto.randomKey(), salt = VaultCrypto.randomBytes(16), aad = "account/vault/root".getBytes();
        byte[] wrapped = VaultCrypto.seal(VaultCrypto.recoveryKey("original recovery password".toCharArray(), salt), master, aad);
        byte[] originalMaster = master.clone(), originalWrapped = wrapped.clone();
        char[] phrase = "original recovery password".toCharArray();
        assertTrue(RecoveryEnvelope.verify(master, phrase, salt, wrapped, aad));
        assertArrayEquals(new char[phrase.length], phrase);
        assertArrayEquals(originalMaster, master);
        assertArrayEquals(originalWrapped, wrapped);
        char[] wrong = "incorrect existing phrase".toCharArray();
        assertFalse(RecoveryEnvelope.verify(master, wrong, salt, wrapped, aad));
        assertArrayEquals(new char[wrong.length], wrong);
        assertFalse(RecoveryEnvelope.verify(VaultCrypto.randomKey(), "original recovery password".toCharArray(), salt, wrapped, aad));
        assertFalse(RecoveryEnvelope.verify(master, "original recovery password".toCharArray(), salt, wrapped, "different/vault/root".getBytes()));
        wrapped[wrapped.length - 1] ^= 1;
        assertFalse(RecoveryEnvelope.verify(master, "original recovery password".toCharArray(), salt, wrapped, aad));
    }
    @Test public void wrongCurrentPhraseOrVaultKeyCannotPublishReplacement() throws Exception {
        byte[] master = VaultCrypto.randomKey(), salt = VaultCrypto.randomBytes(16), aad = "root".getBytes();
        byte[] wrapped = VaultCrypto.seal(VaultCrypto.recoveryKey("original recovery password".toCharArray(), salt), master, aad);
        assertThrows(GeneralSecurityException.class, () -> RecoveryEnvelope.change(master, "incorrect existing phrase".toCharArray(), salt, wrapped, "a new valid recovery phrase".toCharArray(), aad));
        assertThrows(GeneralSecurityException.class, () -> RecoveryEnvelope.change(VaultCrypto.randomKey(), "original recovery password".toCharArray(), salt, wrapped, "a new valid recovery phrase".toCharArray(), aad));
    }
}
