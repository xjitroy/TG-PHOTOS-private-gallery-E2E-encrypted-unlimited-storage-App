package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class AnalysisImageSizeTest {
 @Test public void largeOriginalIsBoundedByPixelsAndEdgeWithoutCropping() {
  var s=AnalysisImageSize.fit(8000,6000,2400,4000000);
  assertTrue(s.width()<=2400 && (long)s.width()*s.height()<=4000000);
  assertEquals(4d/3,s.width()/(double)s.height(),.002);
 }
 @Test public void smallPhotoIsNotUpscaledAndPanoramaCannotOverflow() {
  assertEquals(new AnalysisImageSize.Size(100,200),AnalysisImageSize.fit(100,200,2400,4000000));
  var s=AnalysisImageSize.fit(Integer.MAX_VALUE,Integer.MAX_VALUE,2400,4000000);
  assertTrue(s.width()>0 && (long)s.width()*s.height()<=4000000);
 }
 @Test public void invalidBoundsFailBeforeBitmapAllocation() {
  assertThrows(IllegalArgumentException.class,()->AnalysisImageSize.fit(0,100,2400,4000000));
 }
}
