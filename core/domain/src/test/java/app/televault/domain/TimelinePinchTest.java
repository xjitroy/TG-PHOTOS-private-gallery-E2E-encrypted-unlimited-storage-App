package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class TimelinePinchTest {
 @Test public void thousandsOfMotionEventsOnlyPublishCrossedColumnBoundaries(){var p=new TimelinePinch(6);int changes=0,last=6;for(int i=0;i<2000;i++){int next=p.update(1.001f);if(next!=0){assertTrue(next<last);last=next;changes++;}}assertEquals(2,last);assertEquals(4,changes);}
 @Test public void tinyMotionAccumulatesAndCanReverseWithoutLosingTheFraction(){var p=new TimelinePinch(3);assertEquals(0,p.update(Float.NaN));int changes=0;for(int i=0;i<1000;i++)if(p.update(.999f)!=0)changes++;assertEquals(3,changes);for(int i=0;i<2000;i++)if(p.update(1.001f)!=0)changes++;assertEquals(7,changes);}
}
