package app.televault.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryCredentialsTest {
    private static final String PASSWORD="my original secure password", PATTERN=PatternRecovery.encode("12369");
    private static final byte[] AAD="account/vault/root".getBytes();
    private RecoveryCredentials.Slot slot(byte[] master,String secret,RecoveryCredentials.Kind kind) throws Exception {
        RecoveryEnvelope.Changed c=RecoveryEnvelope.rewrap(master,secret.toCharArray(),AAD);
        return new RecoveryCredentials.Slot(kind,c.salt(),c.wrapped());
    }
    @Test public void eitherCredentialUnlocksAndCanResetOtherWithoutChangingMaster() throws Exception {
        byte[] master=VaultCrypto.randomKey();
        var primary=slot(master,PASSWORD,RecoveryCredentials.Kind.PASSWORD);
        var alternate=slot(master,PATTERN,RecoveryCredentials.Kind.PATTERN);
        assertArrayEquals(master,RecoveryCredentials.open(PASSWORD.toCharArray(),primary,alternate,AAD));
        char[] pattern=PATTERN.toCharArray();
        assertArrayEquals(master,RecoveryCredentials.open(pattern,primary,alternate,AAD));
        assertArrayEquals(new char[pattern.length],pattern);
        var resetPassword=RecoveryCredentials.replace(master,primary,alternate,PATTERN.toCharArray(),"654321".toCharArray(),AAD);
        assertArrayEquals(alternate.wrapped(),resetPassword.alternate().wrapped());
        assertArrayEquals(master,RecoveryCredentials.open("654321".toCharArray(),resetPassword.primary(),resetPassword.alternate(),AAD));
        assertArrayEquals(master,RecoveryCredentials.open(PATTERN.toCharArray(),resetPassword.primary(),resetPassword.alternate(),AAD));
        assertFalse(RecoveryCredentials.verify(master,PASSWORD.toCharArray(),resetPassword.primary(),resetPassword.alternate(),AAD));
        String replacementPattern=PatternRecovery.encode("14789");
        var resetPattern=RecoveryCredentials.replace(master,resetPassword.primary(),resetPassword.alternate(),"654321".toCharArray(),replacementPattern.toCharArray(),AAD);
        assertArrayEquals(resetPassword.primary().wrapped(),resetPattern.primary().wrapped());
        assertArrayEquals(master,RecoveryCredentials.open(replacementPattern.toCharArray(),resetPattern.primary(),resetPattern.alternate(),AAD));
        assertFalse(RecoveryCredentials.verify(master,PATTERN.toCharArray(),resetPattern.primary(),resetPattern.alternate(),AAD));
    }
    @Test public void legacyPasswordOrPatternCanAddOtherCredential() throws Exception {
        byte[] master=VaultCrypto.randomKey();
        for (String legacy:new String[]{PASSWORD,PATTERN}) {
            var old=slot(master,legacy,RecoveryCredentials.Kind.UNKNOWN);
            String next=legacy.equals(PASSWORD)?PATTERN:PASSWORD;
            var updated=RecoveryCredentials.replace(master,old,null,legacy.toCharArray(),next.toCharArray(),AAD);
            assertNotNull(updated.alternate());
            assertNotEquals(updated.primary().kind(),updated.alternate().kind());
            assertArrayEquals(old.wrapped(),updated.primary().wrapped());
            assertArrayEquals(master,RecoveryCredentials.open(legacy.toCharArray(),updated.primary(),updated.alternate(),AAD));
            assertArrayEquals(master,RecoveryCredentials.open(next.toCharArray(),updated.primary(),updated.alternate(),AAD));
        }
    }
    @Test public void wrongCredentialAccountOrMasterCannotAuthorizeReplacement() throws Exception {
        byte[] master=VaultCrypto.randomKey();
        var primary=slot(master,PASSWORD,RecoveryCredentials.Kind.PASSWORD);
        var alternate=slot(master,PATTERN,RecoveryCredentials.Kind.PATTERN);
        char[] wrong="incorrect password".toCharArray(), next="123456".toCharArray();
        assertThrows(GeneralSecurityException.class,()->RecoveryCredentials.replace(master,primary,alternate,wrong,next,AAD));
        assertArrayEquals(new char[wrong.length],wrong);assertArrayEquals(new char[next.length],next);
        assertFalse(RecoveryCredentials.verify(VaultCrypto.randomKey(),PATTERN.toCharArray(),primary,alternate,AAD));
        assertFalse(RecoveryCredentials.verify(master,PATTERN.toCharArray(),primary,alternate,"other/account/root".getBytes()));
        alternate.wrapped()[59]^=1;
        assertFalse(RecoveryCredentials.verify(master,PATTERN.toCharArray(),primary,alternate,AAD));
        assertArrayEquals(master,RecoveryCredentials.open(PASSWORD.toCharArray(),primary,alternate,AAD));
    }
    @org.junit.Test public void migratePatternToPasswordRemovesWeakWrapperAndPreservesMaster() throws Exception {
        byte[] key=VaultCrypto.randomKey(),aad="owner/vault".getBytes();
        var old=RecoveryEnvelope.rewrap(key,PatternRecovery.encode("1234").toCharArray(),aad);
        var pattern=new RecoveryCredentials.Slot(RecoveryCredentials.Kind.PATTERN,old.salt(),old.wrapped());
        var next=RecoveryCredentials.passwordOnly(key,pattern,null,PatternRecovery.encode("1234").toCharArray(),"Secure@Password_123".toCharArray(),aad);
        org.junit.Assert.assertNull(next.alternate());
        org.junit.Assert.assertArrayEquals(key,RecoveryCredentials.open("Secure@Password_123".toCharArray(),next.primary(),null,aad));
        org.junit.Assert.assertFalse(RecoveryCredentials.verify(key,PatternRecovery.encode("1234").toCharArray(),next.primary(),null,aad));
        org.junit.Assert.assertThrows(java.security.GeneralSecurityException.class,()->RecoveryCredentials.passwordOnly(key,pattern,null,PatternRecovery.encode("4321").toCharArray(),"Secure@Password_123".toCharArray(),aad));
    }
}
