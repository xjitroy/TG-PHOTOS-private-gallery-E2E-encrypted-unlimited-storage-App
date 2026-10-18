package app.televault.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;

/** Invoke only with the hardware-authorized cipher returned by the biometric callback.
 * AAD is supplied AFTER authentication, preserving the existing envelope bytes and scope. */
public final class BiometricOperation {
    private BiometricOperation() { }
    public static byte[] finish(Cipher authenticated, byte[] aad, byte[] secret) throws GeneralSecurityException {
        try { authenticated.updateAAD(aad); return authenticated.doFinal(secret); }
        finally { Arrays.fill(secret,(byte)0); }
    }
}
