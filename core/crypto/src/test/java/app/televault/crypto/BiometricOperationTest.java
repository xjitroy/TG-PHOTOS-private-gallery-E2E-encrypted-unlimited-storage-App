package app.televault.crypto;
import org.junit.Test;
import static org.junit.Assert.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
public class BiometricOperationTest {
 private final byte[] key=new byte[32],iv=new byte[12],aad="TGPhotos/private-vault/biometric/v1/1/photos/vault".getBytes(StandardCharsets.UTF_8);
 private Cipher cipher(int mode)throws Exception{var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));return c;}
 @Test public void existingWrappedMasterStillOpensWithDeferredAad()throws Exception{byte[] master=new byte[32];Arrays.fill(master,(byte)7);var old=cipher(Cipher.ENCRYPT_MODE);old.updateAAD(aad);byte[] wrapped=old.doFinal(master);assertEquals(48,wrapped.length);assertArrayEquals(master,BiometricOperation.finish(cipher(Cipher.DECRYPT_MODE),aad,wrapped));assertArrayEquals(new byte[48],wrapped);}
 @Test public void enrollmentProducesTheSameLegacyPayloadAfterAuthentication()throws Exception{byte[] master=new byte[32];Arrays.fill(master,(byte)9);var old=cipher(Cipher.ENCRYPT_MODE);old.updateAAD(aad);byte[] expected=old.doFinal(master);assertArrayEquals(expected,BiometricOperation.finish(cipher(Cipher.ENCRYPT_MODE),aad,master));assertArrayEquals(new byte[32],master);}
 @Test public void anotherVaultCannotOpenThePayloadAndFailedInputIsWiped()throws Exception{byte[] wrapped=BiometricOperation.finish(cipher(Cipher.ENCRYPT_MODE),aad,new byte[32]);try{BiometricOperation.finish(cipher(Cipher.DECRYPT_MODE),"another-vault".getBytes(StandardCharsets.UTF_8),wrapped);fail();}catch(javax.crypto.AEADBadTagException expected){}assertArrayEquals(new byte[48],wrapped);}
 @Test public void evenAnUninitializedCipherCannotRetainTheMaster()throws Exception{byte[] master=new byte[32];Arrays.fill(master,(byte)3);try{BiometricOperation.finish(Cipher.getInstance("AES/GCM/NoPadding"),aad,master);fail();}catch(IllegalStateException expected){}assertArrayEquals(new byte[32],master);}
}
