package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class PhotoTextHoldTest {
 @Test public void requiresFiveSecondsExactlyOnce() { var hold=new PhotoTextHold();hold.begin(100,5,5,8);assertFalse(hold.fire(5099));assertTrue(hold.fire(5100));assertFalse(hold.fire(6000)); }
 @Test public void motionCancelsHold() { var hold=new PhotoTextHold();hold.begin(0,0,0,8);hold.update(9,0,1,true);assertFalse(hold.fire(5000)); }
 @Test public void releasePinchAndDisposalCancel() { var hold=new PhotoTextHold();hold.begin(0,0,0,8);hold.update(0,0,2,true);assertFalse(hold.fire(5000));hold.begin(0,0,0,8);hold.update(0,0,1,false);assertFalse(hold.fire(5000));hold.begin(0,0,0,8);hold.cancel();assertFalse(hold.fire(5000)); }
 @Test public void timerCannotFireBeforeNewPressOrBackwardClock() { var hold=new PhotoTextHold();hold.begin(4000,0,0,8);assertFalse(hold.fire(3000));hold.begin(9000,0,0,8);assertFalse(hold.fire(10000)); }
}
