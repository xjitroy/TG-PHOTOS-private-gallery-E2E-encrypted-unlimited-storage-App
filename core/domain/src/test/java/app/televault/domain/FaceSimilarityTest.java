package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class FaceSimilarityTest {
 @Test public void normalizationAndOrthogonalSimilarity() { float[] a=new float[128], b=new float[128]; a[0]=3;b[1]=9; assertEquals(1,FaceSimilarity.cosine(a,a),1e-6);assertEquals(0,FaceSimilarity.cosine(a,b),1e-6); assertEquals(3,a[0],0); }
 @Test public void rejectsInvalidVectors() { assertThrows(IllegalArgumentException.class,()->FaceSimilarity.normalized(new float[128])); float[] bad=new float[128];bad[0]=Float.NaN;assertThrows(IllegalArgumentException.class,()->FaceSimilarity.normalized(bad)); }
 @Test public void ambiguityRequiresReview() { assertFalse(FaceSimilarity.confident(.90f,.85f,3));assertFalse(FaceSimilarity.confident(.70f,.20f,3));assertTrue(FaceSimilarity.confident(.90f,.40f,1)); }
}
