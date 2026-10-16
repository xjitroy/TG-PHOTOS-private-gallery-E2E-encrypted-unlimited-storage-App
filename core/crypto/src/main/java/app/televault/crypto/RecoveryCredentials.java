package app.televault.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/** Independently salted password and app-pattern envelopes around the same master key. */
public final class RecoveryCredentials {
    private RecoveryCredentials() {}
    public enum Kind { UNKNOWN, PASSWORD, PATTERN }
    public record Slot(Kind kind, byte[] salt, byte[] wrapped) {
        public Slot {
            if (kind == null || salt == null || salt.length != 16 || wrapped == null || wrapped.length != 60)
                throw new IllegalArgumentException("Invalid recovery slot");
        }
    }
    public record Updated(Slot primary, Slot alternate) {}
    public static Kind kind(char[] secret) {
        String prefix = "TGPhotosPattern/v1:";
        if (secret != null && secret.length >= prefix.length() + 4 && secret.length <= prefix.length() + 9) {
            boolean matches = true;
            for (int i=0; i<prefix.length(); i++) matches &= secret[i] == prefix.charAt(i);
            int seen=0;
            for (int i=prefix.length(); i<secret.length; i++) {
                char c=secret[i];
                if (c<'1' || c>'9') { matches=false; break; }
                int bit=1 << (c-'1');
                if ((seen & bit)!=0) { matches=false; break; }
                seen |= bit;
            }
            if (matches) return Kind.PATTERN;
        }
        return Kind.PASSWORD;
    }
    public static byte[] open(char[] secret, Slot primary, Slot alternate, byte[] context) throws GeneralSecurityException {
        try {
            Slot first = alternate != null && alternate.kind() == kind(secret) ? alternate : primary;
            Slot second = first == primary ? alternate : primary;
            try { return openSlot(secret, first, context); }
            catch (GeneralSecurityException e) {
                if (second == null) throw e;
                return openSlot(secret, second, context);
            }
        } finally { Arrays.fill(secret, '\0'); }
    }
    private static byte[] openSlot(char[] secret, Slot slot, byte[] context) throws GeneralSecurityException {
        byte[] derived = null, key = null;
        try {
            derived = VaultCrypto.recoveryKey(secret, slot.salt());
            key = VaultCrypto.open(derived, slot.wrapped(), context);
            if (key.length != 32) throw new GeneralSecurityException("Invalid master key");
            byte[] opened = key; key = null; return opened;
        } finally {
            if (derived != null) Arrays.fill(derived, (byte)0);
            if (key != null) Arrays.fill(key, (byte)0);
        }
    }
    public static boolean verify(byte[] master, char[] secret, Slot primary, Slot alternate, byte[] context) {
        byte[] opened = null;
        try {
            opened = open(secret, primary, alternate, context);
            return master != null && master.length == 32 && MessageDigest.isEqual(master, opened);
        } catch (GeneralSecurityException | IllegalArgumentException e) { return false; }
        finally { if (opened != null) Arrays.fill(opened, (byte)0); Arrays.fill(secret, '\0'); }
    }
    /** null current is reserved for a previously unlocked phone after fresh device authentication. */
    /** Migrate either old credential to one strong password; do not keep a weak pattern wrapper. */
    public static Updated passwordOnly(byte[] master, Slot primary, Slot alternate, char[] current, char[] next, byte[] context) throws java.security.GeneralSecurityException {
        try {
            if (!NewPasswordPolicy.accepts(next)) throw new IllegalArgumentException("Strong password required");
            if (current != null && !verify(master, current, primary, alternate, context)) throw new java.security.GeneralSecurityException("Incorrect credential");
            RecoveryEnvelope.Changed wrapped=RecoveryEnvelope.rewrap(master,next,context);
            return new Updated(new Slot(Kind.PASSWORD,wrapped.salt(),wrapped.wrapped()),null);
        } finally { if(current!=null)java.util.Arrays.fill(current,'\0');if(next!=null)java.util.Arrays.fill(next,'\0'); }
    }
    public static Updated replace(byte[] master, Slot primary, Slot alternate, char[] current, char[] next, byte[] context) throws GeneralSecurityException {
        Kind currentKind = current == null ? null : kind(current), nextKind = kind(next);
        try {
            if (current != null && !verify(master, current, primary, alternate, context))
                throw new GeneralSecurityException("Incorrect existing credential");
            Kind primaryKind = primary.kind();
            if (primaryKind == Kind.UNKNOWN) {
                primaryKind = alternate != null ? (alternate.kind() == Kind.PATTERN ? Kind.PASSWORD : Kind.PATTERN)
                    : currentKind != null ? currentKind : nextKind;
            }
            Slot retained = new Slot(primaryKind, primary.salt(), primary.wrapped());
            RecoveryEnvelope.Changed wrapped = RecoveryEnvelope.rewrap(master, next, context);
            Slot changed = new Slot(nextKind, wrapped.salt(), wrapped.wrapped());
            return primaryKind == nextKind ? new Updated(changed, alternate) : new Updated(retained, changed);
        } finally {
            if (current != null) Arrays.fill(current, '\0');
            Arrays.fill(next, '\0');
        }
    }
}
