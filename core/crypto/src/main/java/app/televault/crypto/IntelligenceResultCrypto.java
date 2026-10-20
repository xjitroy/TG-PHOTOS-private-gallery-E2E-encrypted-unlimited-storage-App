package app.televault.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** An account/vault/key scoped opaque address. Neither a raw content hash nor a face identifier. */
public final class IntelligenceResultCrypto {
 private IntelligenceResultCrypto() { }
 public static String objectId(byte[] key, long account, String vault, String contentHash) throws GeneralSecurityException {
  if (key == null || key.length != 32 || account <= 0 || vault == null || !vault.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || contentHash == null || contentHash.isEmpty() || contentHash.length() > 128)
   throw new IllegalArgumentException("Invalid analysis address");
  Mac mac=Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec(key,"HmacSHA256"));
  byte[] value=mac.doFinal(("TGDrive/v1/"+account+"/"+vault+"/analysis-object-v1/"+contentHash).getBytes(StandardCharsets.UTF_8));
  StringBuilder result=new StringBuilder(64);
  for (byte b:value) result.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
  java.util.Arrays.fill(value,(byte)0);
  return result.toString();
 }
}
