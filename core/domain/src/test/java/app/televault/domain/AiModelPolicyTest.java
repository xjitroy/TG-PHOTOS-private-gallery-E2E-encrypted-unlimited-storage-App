package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class AiModelPolicyTest {
 @Test public void physicalRamUsesRequestedTiersIncludingAdvertisedEightGb() {
  assertEquals("small",AiModelPolicy.automatic(3*AiModelPolicy.GIB));
  assertEquals("balanced",AiModelPolicy.automatic(3900000000L));
  assertEquals("embedding",AiModelPolicy.automatic(7800000000L));
  assertEquals("embedding",AiModelPolicy.automatic(12*AiModelPolicy.GIB));
  assertEquals("qwen",AiModelPolicy.automatic(12*AiModelPolicy.GIB+1));
  assertEquals("balanced",AiModelPolicy.automatic(0));
 }
 @Test public void explicitChoicePersistsButModelFailureCanChooseSmall() {
  assertEquals("balanced",AiModelPolicy.desired("balanced",16*AiModelPolicy.GIB,false));
  assertEquals("small",AiModelPolicy.desired("qwen",16*AiModelPolicy.GIB,true));
  assertEquals("embedding",AiModelPolicy.desired("auto",8*AiModelPolicy.GIB,false));
 }
 @Test public void noFailedOrStaleReplacementCanRetireWorkingModel() {
  assertTrue(AiModelPolicy.canCommit(true,true,true,true,true));
  for(int missing=0;missing<5;missing++)assertFalse(AiModelPolicy.canCommit(missing!=0,missing!=1,missing!=2,missing!=3,missing!=4));
 }
 @Test public void advertisedRamIsNotAvailableRam() {
  assertFalse(AiModelPolicy.enoughMemory(1500L*1024*1024,400L*1024*1024,"qwen"));
  assertTrue(AiModelPolicy.enoughMemory(1500L*1024*1024,400L*1024*1024,"small"));
 }
}
