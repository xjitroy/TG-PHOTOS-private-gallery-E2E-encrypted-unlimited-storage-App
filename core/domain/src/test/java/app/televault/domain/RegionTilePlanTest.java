package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class RegionTilePlanTest {
 @Test public void fitOverviewContainsTheWholeSource(){var p=RegionTilePlan.create(4000,3000,1080,2400,false,1,2000,1500,3*1024*1024);assertEquals(0,p.left());assertEquals(0,p.top());assertEquals(4000,p.right());assertEquals(3000,p.bottom());assertEquals(4,p.sample());}
 @Test public void panOutsideSourceIsClampedAndOverscanContainsVisibleRegion(){var p=RegionTilePlan.create(4000,3000,1000,1000,false,5,-90000,90000,8*1024*1024);assertTrue(p.left()>=0&&p.top()>=0&&p.right()<=4000&&p.bottom()<=3000);assertTrue(p.left()<=p.visibleLeft()&&p.top()<=p.visibleTop()&&p.right()>=p.visibleRight()&&p.bottom()>=p.visibleBottom());}
 @Test public void quarterTurnUsesSwappedViewportAxes(){var p=RegionTilePlan.create(4000,3000,720,1280,true,1,2000,1500,8*1024*1024);assertEquals(0,p.left());assertEquals(4000,p.right());assertEquals(0,p.top());assertEquals(3000,p.bottom());}
 @Test public void variedHighResolutionPlansRespectPixelAndHeapBudgets(){var random=new java.util.Random(18);for(int i=0;i<2000;i++){int w=1000+random.nextInt(19000),h=1000+random.nextInt(19000);int vw=320+random.nextInt(1100),vh=400+random.nextInt(2200);long budget=(1+random.nextInt(12))*1024L*1024;var p=RegionTilePlan.create(w,h,vw,vh,i%2==0,1+random.nextInt(20),w/2f,h/2f,budget);long pixels=((p.right()-p.left()+(long)p.sample()-1)/p.sample())*((p.bottom()-p.top()+(long)p.sample()-1)/p.sample());assertTrue(pixels*4<=budget);assertTrue(p.sample()>0&&(p.sample()&(p.sample()-1))==0);}}
 @Test(expected=IllegalArgumentException.class) public void malformedDimensionsNeverReachTheNativeDecoder(){RegionTilePlan.create(0,3000,1000,1000,false,1,0,0,1024);}
}
