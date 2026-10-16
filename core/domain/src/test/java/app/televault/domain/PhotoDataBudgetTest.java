package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import static app.televault.domain.PhotoDataBudget.*;
public class PhotoDataBudgetTest {
    @Test public void cellularPhotoLimitIncludesBoundaryAndPreservesFailedReservation() {
        long limit=150L*1024*1024;
        var allowed=reserve("image/jpeg",true,false,limit,limit-1024,1024,false);
        assertEquals(Block.NONE,allowed.block());assertEquals(limit,allowed.reservedBytes());
        var denied=reserve("image/png",true,false,limit,allowed.reservedBytes(),1,false);
        assertEquals(Block.DAILY_PHOTO_LIMIT,denied.block());assertEquals(limit,denied.reservedBytes());
        assertEquals(Block.NONE,reserve("image/jpeg",false,false,limit,limit,200*1024*1024L,false).block());
        assertEquals(limit+1,reserve("image/jpeg",true,false,limit,limit,1,true).reservedBytes());
    }
    @Test public void videoRestrictionDoesNotConsumePhotoBudgetAndOverflowIsSafe() {
        assertEquals(Block.MOBILE_VIDEO_DISABLED,reserve("video/mp4",true,false,100,20,300,false).block());
        assertEquals(20,reserve("video/mp4",true,true,100,20,300,false).reservedBytes());
        assertEquals(20,reserve("application/pdf",true,false,100,20,300,false).reservedBytes());
        assertEquals(Long.MAX_VALUE,reserve("image/jpeg",true,false,Long.MAX_VALUE,Long.MAX_VALUE-1,100,true).reservedBytes());
        assertThrows(IllegalArgumentException.class,()->reserve("image/jpeg",true,false,100,0,-1,false));
    }
}
