package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class FaceGroupingTest {
 @Test public void namedReviewedAndOwnIdentitiesAreNeverAutomaticMergeSources(){assertFalse(FaceGrouping.automatic("Alice",false,false));assertFalse(FaceGrouping.automatic("",true,false));assertFalse(FaceGrouping.automatic("",false,true));assertTrue(FaceGrouping.automatic("",false,false));}
 @Test public void cooccurrenceRejectionsAndCompetingNamesOverrideHighSimilarity(){assertFalse(FaceGrouping.canCombine(.95f,true,false,false));assertFalse(FaceGrouping.canCombine(.95f,false,true,false));assertFalse(FaceGrouping.canCombine(.95f,false,false,true));assertFalse(FaceGrouping.canCombine(.60f,false,false,false));assertTrue(FaceGrouping.canCombine(.75f,false,false,false));}
 @Test public void singlePhotosStayUnsortedUnlessTheUserConfirmsThem(){assertTrue(FaceGrouping.provisional("",false,1));assertTrue(FaceGrouping.provisional("",false,2));assertFalse(FaceGrouping.provisional("",true,1));assertFalse(FaceGrouping.provisional("Named",false,1));assertFalse(FaceGrouping.provisional("",false,3));}
 @Test public void equalDimensionVectorsFromDifferentModelsAreNotCompatible(){assertFalse(FaceGrouping.compatible("mobile-v1","sface-v2"));assertFalse(FaceGrouping.compatible("",""));assertTrue(FaceGrouping.compatible("sface-v2","sface-v2"));}
 @Test public void cloudAndDeviceAliasesOfOnePhotoBlockAutomaticIdentityMerges(){
  var cloud=java.util.Set.of("cloud-uuid");var device=java.util.Set.of("content://media/1");var hash=java.util.Set.of("same-content-sha256");
  assertTrue(FaceGrouping.cooccur(cloud,device,hash,hash));
  assertFalse(FaceGrouping.canCombine(.95f,FaceGrouping.cooccur(cloud,device,hash,hash),false,false));
  assertFalse(FaceGrouping.cooccur(cloud,device,hash,java.util.Set.of("different-content")));
 }
 @Test public void aManualPhotoReassignmentCannotBeUndoneByStrongAutomaticSimilarity(){
  var corrected=java.util.Set.of("corrected-photo-sha256");var empty=java.util.Set.<String>of();
  boolean rejected=FaceGrouping.conflictingTags(corrected,corrected,empty,empty);
  assertTrue(rejected);assertFalse(FaceGrouping.canCombine(.98f,false,rejected,false));
  assertTrue(FaceGrouping.conflictingTags(empty,empty,corrected,corrected));
 }
}
