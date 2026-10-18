package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class FaceSimilarityTest {
 @Test public void normalizationAndOrthogonalSimilarity() { float[] a=new float[128], b=new float[128]; a[0]=3;b[1]=9; assertEquals(1,FaceSimilarity.cosine(a,a),1e-6);assertEquals(0,FaceSimilarity.cosine(a,b),1e-6); assertEquals(3,a[0],0); }
 @Test public void rejectsInvalidVectors() { assertThrows(IllegalArgumentException.class,()->FaceSimilarity.normalized(new float[128])); float[] bad=new float[128];bad[0]=Float.NaN;assertThrows(IllegalArgumentException.class,()->FaceSimilarity.normalized(bad)); }
 @Test public void ambiguityRequiresReview() { assertFalse(FaceSimilarity.confident(.90f,.85f,3));assertFalse(FaceSimilarity.confident(.48f,.20f,3));assertTrue(FaceSimilarity.confident(.90f,.40f,1));assertTrue(FaceSimilarity.confident(.70f,.20f,3));assertFalse(FaceSimilarity.confident(.55f,.52f,1)); }
 @Test public void largeFiniteVectorsRemainUsableAndInputsAreNeverMutated() {
  float[] a = new float[128], b = new float[128]; a[0] = Float.MAX_VALUE; b[0] = Float.MAX_VALUE;
  assertEquals(1, FaceSimilarity.cosine(a, b), 1e-6);
  assertEquals(1, FaceSimilarity.normalized(a)[0], 1e-6);
  assertEquals(Float.MAX_VALUE, a[0], 0);
 }
 @Test public void nonFiniteAndEmptySimilarityCannotCreateMatches() {
  float[] valid = new float[128]; valid[0] = 1;
  assertThrows(IllegalArgumentException.class, () -> FaceSimilarity.cosine(valid, new float[128]));
  float[] invalid = valid.clone(); invalid[2] = Float.POSITIVE_INFINITY;
  assertThrows(IllegalArgumentException.class, () -> FaceSimilarity.cosine(valid, invalid));
 }
}
