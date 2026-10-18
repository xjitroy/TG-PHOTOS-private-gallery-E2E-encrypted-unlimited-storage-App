package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class FaceScanWindowsTest {
    @Test public void smallImagesDoNotNeedlesslyMultiplyNativeInference() {
        assertEquals(java.util.List.of(new FaceScanWindows.Window(0,0,512,384)), FaceScanWindows.forImage(512,384));
    }
    @Test public void originalImageTilesCoverEdgesAndStayBounded() {
        for (int[] size : new int[][] {{2400,1800},{1800,2400},{1200,1200},{2400,100},{769,400}}) {
            var windows = FaceScanWindows.forImage(size[0],size[1]);
            assertTrue(windows.size() > 1 && windows.size() <= 26);
            for (var w : windows) {
                assertTrue(w.left()>=0 && w.top()>=0 && w.width()>0 && w.height()>0);
                assertTrue(w.left()+w.width()<=size[0] && w.top()+w.height()<=size[1]);
            }
            for (int y=0;y<size[1];y+=37) for (int x=0;x<size[0];x+=37) {
                final int xx=x,yy=y;
                assertTrue(windows.subList(1,windows.size()).stream().anyMatch(w->xx>=w.left() && xx<w.left()+w.width() && yy>=w.top() && yy<w.top()+w.height()));
            }
        }
    }
    @Test public void neighboringTilesOverlapToProtectFacesOnTheirBorders() {
        var tiles = FaceScanWindows.forImage(2400,1800).subList(1,21);
        assertTrue(tiles.get(1).left() < tiles.get(0).width());
        assertThrows(IllegalArgumentException.class, () -> FaceScanWindows.forImage(0,400));
    }
}
