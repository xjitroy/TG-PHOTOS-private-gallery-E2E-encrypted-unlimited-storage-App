package app.televault.domain;
import static org.junit.Assert.*;
import org.junit.Test;
public class PhotoColorAdjustmentsTest {
 @Test public void neutralPreservesEveryChannelAndAlpha() {
  assertArrayEquals(new float[]{1,0,0,0,0,0,1,0,0,0,0,0,1,0,0,0,0,0,1,0}, PhotoColorAdjustments.matrix(0,1,1,0), .0001f);
 }
 @Test public void monochromeProducesEqualLuminanceAndWarmthHasOppositeColorShift() {
  float[] mono=PhotoColorAdjustments.matrix(0,0,1,0);
  assertEquals(mono[0],mono[5],.0001f); assertEquals(mono[5],mono[10],.0001f);
  float[] warm=PhotoColorAdjustments.matrix(0,1,1,1);
  assertTrue(warm[0]>warm[12]); assertEquals(1,warm[6],.0001f); assertEquals(1,warm[18],.0001f);
 }
 @Test public void invalidNonfiniteInputsFailBeforeAllocatingAnImage() {
  for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,3}) {
   try { PhotoColorAdjustments.matrix(0,1,invalid,0); fail(); } catch(IllegalArgumentException expected) {}
  }
 }
}
