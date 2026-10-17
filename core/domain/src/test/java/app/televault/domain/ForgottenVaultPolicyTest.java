package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class ForgottenVaultPolicyTest {
    private final String a="11111111-1111-1111-1111-111111111111", b="22222222-2222-2222-2222-222222222222", c="33333333-3333-3333-3333-333333333333";
    @Test public void onlyServerElapsedTimeCanMatureARequest() {
        assertFalse(ForgottenVaultPolicy.due(0,Long.MAX_VALUE));
        assertFalse(ForgottenVaultPolicy.due(1000,999));
        assertFalse(ForgottenVaultPolicy.due(1000,1000+86400000L-1));
        assertTrue(ForgottenVaultPolicy.due(1000,1000+86400000L));
        assertTrue(ForgottenVaultPolicy.due(1000,Long.MAX_VALUE));
    }
    @Test public void photosScopeOwnsOnlyItsProtocolNamespace() {
        for(String value:new String[]{"ROOT:"+a,"PRIVATE_ROOT:"+a,"FILE:"+a+":"+b,"EVENT:"+a+":"+b,"DATA:"+a+":"+b,"DATA:"+a+":"+b+":2","DATA:"+a+":"+b+":"+c+":2:","FILE:PV2:"+a+":"+b,"DATA:PV2:"+a+":"+b+":"+c+":2"}) assertTrue(value,ForgottenVaultPolicy.owns("TGDRIVE_V1_"+value,a,null));
        for(String value:new String[]{"ROOT:"+b,"ROOT:"+a+":extra","FILE:"+a+":"+b+":extra","DATA:"+a+":"+b+":"+c+":-2:","RESET:"+a+":REQUEST","FILE:PV2:"+b+":"+a}) assertFalse(value,ForgottenVaultPolicy.owns("TGDRIVE_V1_"+value,a,null));
        assertFalse(ForgottenVaultPolicy.owns("note TGDRIVE_V1_ROOT:"+a,a,null));
    }
    @Test public void forgottenPrivateVaultCannotDeleteSharedRootsOrSiblingVaults() {
        assertTrue(ForgottenVaultPolicy.owns("TGDRIVE_V1_FILE:PV2:"+a+":"+b,a,b));
        assertTrue(ForgottenVaultPolicy.owns("TGDRIVE_V1_DATA:PV2:"+a+":"+b+":"+c+":0",a,b));
        assertFalse(ForgottenVaultPolicy.owns("TGDRIVE_V1_PRIVATE_ROOT:"+a,a,b));
        assertFalse(ForgottenVaultPolicy.owns("TGDRIVE_V1_FILE:PV2:"+a+":"+c,a,b));
        assertFalse(ForgottenVaultPolicy.owns("TGDRIVE_V1_ROOT:"+a,a,b));
    }
    @Test public void lifecycleMessagesHaveExactBoundaries() {
        assertTrue(ForgottenVaultPolicy.lifecycleMarker("TGDRIVE_V1_RESET:"+a+":REQUEST"));
        assertTrue(ForgottenVaultPolicy.lifecycleMarker("TGDRIVE_V1_RESET:"+a+":CLOCK:"+b));
        assertFalse(ForgottenVaultPolicy.lifecycleMarker("TGDRIVE_V1_RESET:"+a+":REQUEST notes"));
        assertFalse(ForgottenVaultPolicy.lifecycleMarker("TGDRIVE_V1_RESET:bad:REQUEST"));
        assertTrue(OwnedCloudCaption.matches("TGDRIVE_V1_DATA:"+a+":"+b+":"+c+":2:",":"+a+":"+b));
    }
    @Test public void cancellationBeforeServerStartAlwaysWinsEvenAgainstALaterCompletion() {
        assertEquals(ForgottenVaultPolicy.Phase.CANCELLED,ForgottenVaultPolicy.phase(1000,10,11,86401000,12,86401000));
        assertEquals(ForgottenVaultPolicy.Phase.CANCELLED,ForgottenVaultPolicy.phase(1000,10,0,0,0,0));
    }
    @Test public void startedDeletionCannotBeCancelledRetroactively() {
        assertEquals(ForgottenVaultPolicy.Phase.STARTED,ForgottenVaultPolicy.phase(1000,12,11,86401000,0,0));
        assertEquals(ForgottenVaultPolicy.Phase.DONE,ForgottenVaultPolicy.phase(1000,13,11,86401000,12,86401000));
    }
    @Test(expected=IllegalArgumentException.class) public void prematureStartCannotAuthorizeABatch() {
        ForgottenVaultPolicy.phase(1000,0,11,86400999,0,0);
    }
    @Test(expected=IllegalArgumentException.class) public void completionWithoutValidServerStartCannotEraseLocalData() {
        ForgottenVaultPolicy.phase(1000,0,0,0,12,86401000);
    }
 @Test public void normalRetirementIncludesOnlyItsExactPeopleNamespace() {
  String photos="11111111-1111-1111-1111-111111111111",person="22222222-2222-2222-2222-222222222222",revision="33333333-3333-3333-3333-333333333333";
  assertTrue(ForgottenVaultPolicy.owns("TGDRIVE_V1_PEOPLE:"+photos+":"+person+":"+revision,photos,null));
  assertFalse(ForgottenVaultPolicy.owns("TGDRIVE_V1_PEOPLE:"+person+":"+photos+":"+revision,photos,null));
  assertFalse(ForgottenVaultPolicy.owns("TGDRIVE_V1_PEOPLE:"+photos+":"+person+":"+revision,photos,person));
  assertFalse(ForgottenVaultPolicy.owns("TGDRIVE_V1_PEOPLE:"+photos+":"+person+":"+revision+":other",photos,null));
 }
 @Test public void privateAiExclusionsBelongOnlyToTheirNormalPhotosNamespace() { String v="11111111-1111-1111-1111-111111111111",r="22222222-2222-2222-2222-222222222222";String marker="TGDRIVE_V1_AI_PRIVATE:"+v+":"+r;assertTrue(ForgottenVaultPolicy.owns(marker,v,null));assertFalse(ForgottenVaultPolicy.owns(marker,r,null));assertFalse(ForgottenVaultPolicy.owns(marker,v,r));assertFalse(ForgottenVaultPolicy.owns(marker+":extra",v,null)); }
}
