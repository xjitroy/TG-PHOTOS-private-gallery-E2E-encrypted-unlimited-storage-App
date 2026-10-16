package app.televault.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/** Rewrap the existing master key; file encryption keys and ciphertext remain unchanged. */
public final class RecoveryEnvelope {
    private RecoveryEnvelope() {}
    public record Changed(byte[] salt, byte[] wrapped) {}
    /** Verify a sensitive action without changing the envelope or retained vault key. */
    public static boolean verify(byte[] master, char[] phrase, byte[] salt,
                                 byte[] wrapped, byte[] context) {
        byte[] derived = null, opened = null;
        try {
            if (master == null || master.length != 32) return false;
            derived = VaultCrypto.recoveryKey(phrase, salt);
            opened = VaultCrypto.open(derived, wrapped, context);
            return MessageDigest.isEqual(master, opened);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        } finally {
            if (derived != null) Arrays.fill(derived, (byte) 0);
            if (opened != null) Arrays.fill(opened, (byte) 0);
            Arrays.fill(phrase, '\0');
        }
    }
    /** Reset only using a retained, already-unlocked key after a fresh phone challenge. */
    public static Changed rewrap(byte[] master, char[] nextPhrase, byte[] context)
            throws GeneralSecurityException {
        byte[] derived = null;
        try {
            if (master == null || master.length != 32) throw new GeneralSecurityException("Invalid vault key");
            byte[] salt = VaultCrypto.randomBytes(16);
            derived = VaultCrypto.recoveryKey(nextPhrase, salt);
            return new Changed(salt, VaultCrypto.seal(derived, master, context));
        } finally {
            if (derived != null) Arrays.fill(derived, (byte) 0);
            Arrays.fill(nextPhrase, '\0');
        }
    }
    public static Changed change(byte[] master, char[] currentPhrase, byte[] currentSalt,
                                 byte[] currentWrapped, char[] newPhrase, byte[] context)
            throws GeneralSecurityException {
        byte[] currentKey = null, confirmed = null, nextKey = null;
        try {
            currentKey = VaultCrypto.recoveryKey(currentPhrase, currentSalt);
            confirmed = VaultCrypto.open(currentKey, currentWrapped, context);
            if (master.length != 32 || !MessageDigest.isEqual(master, confirmed))
                throw new GeneralSecurityException("Recovery envelope does not match this vault");
            byte[] salt = VaultCrypto.randomBytes(16);
            nextKey = VaultCrypto.recoveryKey(newPhrase, salt);
            return new Changed(salt, VaultCrypto.seal(nextKey, master, context));
        } finally {
            if (currentKey != null) Arrays.fill(currentKey, (byte) 0);
            if (confirmed != null) Arrays.fill(confirmed, (byte) 0);
            if (nextKey != null) Arrays.fill(nextKey, (byte) 0);
            Arrays.fill(currentPhrase, '\0');
            Arrays.fill(newPhrase, '\0');
        }
    }
}
