package app.televault.crypto;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** Mandatory primitive checks for separate private-Vault wrapper and file-key domains. */
public class PrivateVaultIsolationTest {
    @Test public void passwordAndOfflineRecoveryOpenSameIndependentMaster() throws Exception {
        byte[] key=VaultCrypto.randomKey(), cloud=VaultCrypto.randomKey();
        byte[] context="TGPhotos/private-vault/v1/owner".getBytes(StandardCharsets.UTF_8);
        var password=RecoveryEnvelope.rewrap(key,"PrivateVault@123".toCharArray(),context);
        var recovery=RecoveryEnvelope.rewrap(key,"a1b2c3d4e5f6a7b8c9d0e1f2".toCharArray(),context);
        byte[] a=VaultCrypto.recoveryKey("PrivateVault@123".toCharArray(),password.salt());
        byte[] b=VaultCrypto.recoveryKey("a1b2c3d4e5f6a7b8c9d0e1f2".toCharArray(),recovery.salt());
        assertArrayEquals(key,VaultCrypto.open(a,password.wrapped(),context));
        assertArrayEquals(key,VaultCrypto.open(b,recovery.wrapped(),context));
        assertFalse(java.util.Arrays.equals(key,cloud));
        try { VaultCrypto.open(a,password.wrapped(),"TGPhotos/cloud/owner".getBytes(StandardCharsets.UTF_8));fail("Domain substitution accepted"); } catch(GeneralSecurityException expected) {}
        try { VaultCrypto.open(cloud,password.wrapped(),context);fail("Cloud master unlocked private envelope"); } catch(GeneralSecurityException expected) {}
    }
    @Test public void fileKeysRemainEncryptedAndMetadataCannotBeMovedBetweenObjects() throws Exception {
        byte[] key=VaultCrypto.randomKey(), file=VaultCrypto.randomKey();
        byte[] context="TGPhotos/private-vault/metadata/v1/object-A".getBytes(StandardCharsets.UTF_8);
        byte[] sealed=VaultCrypto.seal(key,file,context);assertArrayEquals(file,VaultCrypto.open(key,sealed,context));
        try { VaultCrypto.open(key,sealed,"TGPhotos/private-vault/metadata/v1/object-B".getBytes(StandardCharsets.UTF_8));fail("Metadata substitution accepted"); } catch(GeneralSecurityException expected) {}
        sealed[sealed.length-1]^=1;
        try { VaultCrypto.open(key,sealed,context);fail("Tampered file key accepted"); } catch(GeneralSecurityException expected) {}
    }
}
