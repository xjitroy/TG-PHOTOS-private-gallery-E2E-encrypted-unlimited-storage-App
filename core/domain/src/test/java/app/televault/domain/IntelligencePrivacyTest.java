package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class IntelligencePrivacyTest {
 @Test public void idExcludesDeviceAndCloudRecordsWithoutAHash() { assertTrue(IntelligencePrivacy.excluded("content://media/7","",Set.of("content://media/7"),Set.of())); }
 @Test public void contentHashExcludesDuplicateAndRestoredIds() { assertTrue(IntelligencePrivacy.excluded("new-cloud-id","verified-sha",Set.of("old-id"),Set.of("verified-sha")));assertFalse(IntelligencePrivacy.excluded("new-id","different",Set.of("old-id"),Set.of("verified-sha"))); }
 @Test public void missingHashesAreNotWildcardMatches() { assertFalse(IntelligencePrivacy.excluded("ordinary","",Set.of(),Set.of(""))); }
 @Test public void cleanupCaptionCannotEscapePersonOrVault() { String v="11111111-1111-1111-1111-111111111111",p="22222222-2222-2222-2222-222222222222",r="33333333-3333-3333-3333-333333333333";String c="TGDRIVE_V1_PEOPLE:"+v+":"+p+":"+r;assertTrue(IntelligencePrivacy.ownedPersonCaption(c,v,p));assertFalse(IntelligencePrivacy.ownedPersonCaption(c,p,v));assertFalse(IntelligencePrivacy.ownedPersonCaption(c+":other",v,p)); }
 @Test public void sameMediaStoreRowOnAnotherPhoneIsNotTheSamePrivateSource() { String a="11111111-1111-1111-1111-111111111111",b="22222222-2222-2222-2222-222222222222";assertNotEquals(IntelligencePrivacy.identity("content://media/7",a),IntelligencePrivacy.identity("content://media/7",b)); }
 @Test public void cloudIdsAndAlreadyScopedReferencesKeepTheirIdentity() { String a="11111111-1111-1111-1111-111111111111",b="22222222-2222-2222-2222-222222222222";assertEquals(a,IntelligencePrivacy.identity(a,b));String scoped=IntelligencePrivacy.identity("content://media/7",a);assertEquals(scoped,IntelligencePrivacy.identity(scoped,b)); }
}
