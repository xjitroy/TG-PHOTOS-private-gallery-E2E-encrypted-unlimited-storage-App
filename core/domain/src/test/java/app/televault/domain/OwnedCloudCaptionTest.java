package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class OwnedCloudCaptionTest {
 private final String a="11111111-1111-1111-1111-111111111111",b="22222222-2222-2222-2222-222222222222",c="33333333-3333-3333-3333-333333333333";
 @Test public void normalOwnershipIncludesLegacyAndMultipartOnly(){String owner=":"+a+":"+b;assertTrue(OwnedCloudCaption.matches("TGDRIVE_V1_FILE"+owner,owner));assertTrue(OwnedCloudCaption.matches("TGDRIVE_V1_DATA"+owner,owner));assertTrue(OwnedCloudCaption.matches("TGDRIVE_V1_DATA"+owner+":42",owner));}
 @Test public void privateOwnershipSeparatesRootsAndDataObjects(){String owner=":PV2:"+a+":"+b;assertTrue(OwnedCloudCaption.matches("TGDRIVE_V1_FILE"+owner,owner));assertTrue(OwnedCloudCaption.matches("TGDRIVE_V1_DATA"+owner+":"+c+":0",owner));assertFalse(OwnedCloudCaption.matches("TGDRIVE_V1_DATA"+owner,owner));assertFalse(OwnedCloudCaption.matches("TGDRIVE_V1_PRIVATE_ROOT:"+a,owner));}
 @Test public void embeddedMarkersPrefixSiblingsAndAlteredIdsCannotAuthorizeDeletion(){String owner=":"+a+":"+b;for(String caption:new String[]{"other TGDRIVE_V1_FILE"+owner,"TGDRIVE_V1_FILE"+owner+":other","TGDRIVE_V1_DATA"+owner+"extra","TGDRIVE_V1_DATA:"+a+":"+c+":0","TGDRIVE_V1_DATA"+owner+":-1","TGDRIVE_V1_DATA"+owner+":0 note"})assertFalse(OwnedCloudCaption.matches(caption,owner));assertFalse(OwnedCloudCaption.matches("TGDRIVE_V1_FILE"+owner,":bad"));}
}
