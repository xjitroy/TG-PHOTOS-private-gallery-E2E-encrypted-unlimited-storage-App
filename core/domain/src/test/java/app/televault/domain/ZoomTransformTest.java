package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class ZoomTransformTest {
 @Test public void focalPointDoesNotDriftAcrossSuccessiveZooms(){var z=new ZoomTransform(6);z.viewport(1000,800);z.transform(2,750,400,0,0);assertEquals(2,z.scale(),0);assertEquals(-250,z.x(),.001);z.transform(1.5f,750,400,0,0);assertEquals(-500,z.x(),.001);assertEquals(750,250*z.scale()+500+z.x(),.001);}
 @Test public void hugePanIsBoundedToTheViewport(){var z=new ZoomTransform(6);z.viewport(1000,800);z.transform(3,500,400,1e7f,-1e7f);assertEquals(1000,z.x(),.001);assertEquals(-800,z.y(),.001);}
 @Test public void pinchingBackToFitRemovesPan(){var z=new ZoomTransform(6);z.viewport(1000,800);z.transform(4,600,300,100,200);z.transform(.001f,900,700,0,0);assertEquals(1,z.scale(),0);assertEquals(0,z.x(),0);assertEquals(0,z.y(),0);}
 @Test public void resizingReboundsWithoutResettingZoom(){var z=new ZoomTransform(6);z.viewport(1000,800);z.transform(3,500,400,1e7f,1e7f);z.viewport(500,600);assertEquals(3,z.scale(),0);assertEquals(500,z.x(),.001);assertEquals(600,z.y(),.001);}
 @Test public void invalidMotionCannotPoisonTheRenderer(){var z=new ZoomTransform(6);z.viewport(1000,800);z.transform(Float.NaN,0,0,0,0);z.transform(2,Float.NaN,0,0,0);z.transform(0,0,0,0,0);assertEquals(1,z.scale(),0);assertEquals(0,z.x(),0);}
 @Test public void repeatedPinchPanStaysFiniteAndWithinBounds(){var z=new ZoomTransform(4);z.viewport(1080,2400);for(int i=0;i<10000;i++){z.transform(i%2==0?1.03f:.99f,i%1080,i%2400,31,-18);assertTrue(Float.isFinite(z.x()));assertTrue(z.scale()>=1&&z.scale()<=4);assertTrue(Math.abs(z.x())<=1080*(z.scale()-1)/2+.01);assertTrue(Math.abs(z.y())<=2400*(z.scale()-1)/2+.01);}}
 @Test public void resetRetainsViewportForTheNextGesture(){var z=new ZoomTransform(6);z.viewport(1000,800);z.transform(5,700,300,12,33);z.reset();z.transform(2,750,400,0,0);assertEquals(-250,z.x(),.001);}
}
