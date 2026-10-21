package app.televault.domain;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class AiVectorSearchTest {
 @Test public void quantizationPreservesDirectionAndIgnoresInputMagnitude() {
  float[] q=new float[256];q[0]=3;q[1]=4;
  assertEquals(1f,AiVectorSearch.score(AiVectorSearch.quantize(q),q),0.0001f);
  float[] opposite=new float[256];opposite[0]=-3;opposite[1]=-4;
  assertEquals(-1f,AiVectorSearch.score(AiVectorSearch.quantize(q),opposite),0.0001f);
 }
 @Test public void unrelatedHighBaselineScoresDoNotFillTheResultGrid() {
  // Real synthetic-fixture host scores, not a claim of phone accuracy.
  for(float[] pair:new float[][]{{0.769186f,0.676726f},{0.780591f,0.693438f},{0.779616f,0.691947f}}) {
   Map<String,Float> found=AiVectorSearch.shortlist(Map.of("red",pair[0],"blue",pair[1]));
   assertEquals(Set.of("red"),found.keySet());
  }
 }
 @Test public void invalidEmbeddingsNeverProducePlausibleMatches() {
  float[] bad=new float[256];bad[0]=Float.NaN;
  assertTrue(Float.isNaN(AiVectorSearch.score(new byte[256],bad)));
  try { AiVectorSearch.quantize(bad);fail(); } catch(IllegalArgumentException expected) { }
  assertTrue(AiVectorSearch.shortlist(Map.of("bad",Float.POSITIVE_INFINITY,"weak",0.2f)).isEmpty());
 }
 @Test public void largeLibrariesReturnABoundedDeterministicShortlist() {
  Map<String,Float> rows=new HashMap<>();for(int i=0;i<10000;i++)rows.put(String.format(Locale.ROOT,"%05d",i),0.9f);
  Map<String,Float> result=AiVectorSearch.shortlist(rows);assertEquals(100,result.size());assertEquals("00000",result.keySet().iterator().next());
 }
}
