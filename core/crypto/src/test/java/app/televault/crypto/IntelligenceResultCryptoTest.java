package app.televault.crypto;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
public class IntelligenceResultCryptoTest {
 private static final String VAULT="11111111-1111-1111-1111-111111111111";
 @Test public void opaqueAddressIsStableAndScoped() throws Exception {
  byte[] key=VaultCrypto.randomKey();
  String id=IntelligenceResultCrypto.objectId(key,1,VAULT,"photo-content-hash");
  assertTrue(id.matches("[0-9a-f]{64}"));
  assertEquals(id,IntelligenceResultCrypto.objectId(key.clone(),1,VAULT,"photo-content-hash"));
  assertNotEquals(id,IntelligenceResultCrypto.objectId(key,2,VAULT,"photo-content-hash"));
  assertNotEquals(id,IntelligenceResultCrypto.objectId(key,1,"22222222-2222-2222-2222-222222222222","photo-content-hash"));
  assertNotEquals(id,IntelligenceResultCrypto.objectId(VaultCrypto.randomKey(),1,VAULT,"photo-content-hash"));
  assertNotEquals(id,IntelligenceResultCrypto.objectId(key,1,VAULT,"other-content"));
 }
 @Test public void resultCipherRejectsTamperOtherPhotoAccountAndNamespace() throws Exception {
  byte[] key=VaultCrypto.randomKey(),plain="private OCR and face result".getBytes(StandardCharsets.UTF_8);
  String id=IntelligenceResultCrypto.objectId(key,1,VAULT,"hash");
  String context="TGDrive/v1/1/"+VAULT+"/ocr-v1/analysis-v1/"+id;
  byte[] aad=context.getBytes(StandardCharsets.UTF_8),sealed=VaultCrypto.seal(key,plain,aad);
  assertArrayEquals(plain,VaultCrypto.open(key,sealed,aad));
  assertFalse(java.util.Arrays.equals(sealed,VaultCrypto.seal(key,plain,aad)));
  sealed[sealed.length-1]^=1;
  assertThrows(Exception.class,()->VaultCrypto.open(key,sealed,aad));
  byte[] valid=VaultCrypto.seal(key,plain,aad);
  for(String wrong:new String[]{context+"other-photo",context.replace("/1/","/2/"),context.replace("/analysis-v1/","/people-v1/")})
   assertThrows(Exception.class,()->VaultCrypto.open(key,valid,wrong.getBytes(StandardCharsets.UTF_8)));
 }
 @Test public void invalidAddressInputsAreRejected() {
  assertThrows(Exception.class,()->IntelligenceResultCrypto.objectId(new byte[16],1,VAULT,"hash"));
  assertThrows(Exception.class,()->IntelligenceResultCrypto.objectId(new byte[32],1,"other/vault","hash"));
  assertThrows(Exception.class,()->IntelligenceResultCrypto.objectId(new byte[32],1,VAULT,""));
 }
}
