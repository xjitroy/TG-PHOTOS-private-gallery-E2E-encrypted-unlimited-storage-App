package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class IntelligenceResultPolicyTest {
 @Test public void cardsAndDocumentsStayLocal() {
  assertTrue(IntelligenceResultPolicy.localOnly("আধার 2345 6789 0123", "", false));
  assertTrue(IntelligenceResultPolicy.localOnly("ABCDE1234F", "", false));
  assertTrue(IntelligenceResultPolicy.localOnly("1234 5678 9012 3456", "", false));
  assertTrue(IntelligenceResultPolicy.localOnly("", "document", false));
  assertTrue(IntelligenceResultPolicy.localOnly("one two three four five six seven eight nine ten eleven twelve", "", false));
 }
 @Test public void ordinaryPhotosMaySyncButVideoPreviewCannotCertifyPrivacy() {
  assertFalse(IntelligenceResultPolicy.localOnly("Happy birthday", "beach sea", false));
  assertTrue(IntelligenceResultPolicy.localOnly("", "beach", true));
 }
 @Test public void ownershipRequiresExactOpaqueCaption() {
  String vault="11111111-1111-1111-1111-111111111111", object=new String(new char[64]).replace('\0','a');
  String caption="TGDRIVE_V1_ANALYSIS:"+vault+":"+object;
  assertTrue(IntelligenceResultPolicy.ownedCaption(caption,vault,object));
  assertFalse(IntelligenceResultPolicy.ownedCaption(caption+":extra",vault,object));
  assertFalse(IntelligenceResultPolicy.ownedCaption(caption,"22222222-2222-2222-2222-222222222222",object));
  assertFalse(IntelligenceResultPolicy.ownedCaption(caption,vault,"raw-content-hash"));
  assertTrue(ForgottenVaultPolicy.owns(caption,vault,null));
  assertFalse(ForgottenVaultPolicy.owns(caption,vault,"22222222-2222-2222-2222-222222222222"));
 }
}
