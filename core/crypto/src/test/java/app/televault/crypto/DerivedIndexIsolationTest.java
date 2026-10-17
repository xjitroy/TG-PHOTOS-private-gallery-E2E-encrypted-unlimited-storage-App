package app.televault.crypto;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
public class DerivedIndexIsolationTest {
 private byte[] aad(String owner,String vault,String kind) { return ("TGDrive/v1/"+owner+"/"+vault+"/"+kind).getBytes(StandardCharsets.UTF_8); }
 @Test public void peopleAndOcrPurposesAreNotInterchangeable() throws Exception {
  byte[] key=VaultCrypto.randomKey(),body="Synthetic face reference and private name".getBytes(StandardCharsets.UTF_8);
  byte[] encrypted=VaultCrypto.seal(key,body,aad("7","vault-a","people-v1/person/revision"));
  assertArrayEquals(body,VaultCrypto.open(key,encrypted,aad("7","vault-a","people-v1/person/revision")));
  assertThrows(java.security.GeneralSecurityException.class,()->VaultCrypto.open(key,encrypted,aad("7","vault-a","ocr-v1/person/revision")));
  Arrays.fill(key,(byte)0);
 }
 @Test public void copiedCipherCannotCrossAccountOrVault() throws Exception {
  byte[] key=VaultCrypto.randomKey(),body="Restaurant private OCR text".getBytes(StandardCharsets.UTF_8);
  byte[] encrypted=VaultCrypto.seal(key,body,aad("7","vault-a","ocr-v1/photo"));
  assertThrows(java.security.GeneralSecurityException.class,()->VaultCrypto.open(key,encrypted,aad("8","vault-a","ocr-v1/photo")));
  assertThrows(java.security.GeneralSecurityException.class,()->VaultCrypto.open(key,encrypted,aad("7","vault-b","ocr-v1/photo")));
  Arrays.fill(key,(byte)0);
 }
 @Test public void tamperedDerivedTextFailsAuthentication() throws Exception {
  byte[] key=VaultCrypto.randomKey(),aad=aad("7","vault-a","ocr-v1/photo");byte[] encrypted=VaultCrypto.seal(key,"Synthetic text".getBytes(StandardCharsets.UTF_8),aad);encrypted[encrypted.length-1]^=1;
  assertThrows(java.security.GeneralSecurityException.class,()->VaultCrypto.open(key,encrypted,aad));Arrays.fill(key,(byte)0);
 }
 @Test public void privateExclusionJournalIsNotAnOcrOrFaceRecord() throws Exception {
  byte[] key=VaultCrypto.randomKey(),body="Synthetic excluded photo ID and SHA256".getBytes(StandardCharsets.UTF_8);
  byte[] encrypted=VaultCrypto.seal(key,body,aad("7","vault-a","ai-private-v1/operation"));
  assertArrayEquals(body,VaultCrypto.open(key,encrypted,aad("7","vault-a","ai-private-v1/operation")));
  assertThrows(java.security.GeneralSecurityException.class,()->VaultCrypto.open(key,encrypted,aad("7","vault-a","ocr-v1/operation")));
  assertThrows(java.security.GeneralSecurityException.class,()->VaultCrypto.open(key,encrypted,aad("7","vault-a","people-v1/operation")));Arrays.fill(key,(byte)0);
 }
}
