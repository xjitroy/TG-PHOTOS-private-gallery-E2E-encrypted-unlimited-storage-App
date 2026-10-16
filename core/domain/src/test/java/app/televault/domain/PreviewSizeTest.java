package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewSizeTest {
    @Test public void normalAndLargeCameraPreviewsFitWithoutUpscaling() {
        assertEquals(new PreviewSize.Size(640,480), PreviewSize.fit(640,480));
        for (int[] dimensions : new int[][]{{8000,6000},{6000,8000},{4096,4096},{1920,1080}}) {
            var result = PreviewSize.fit(dimensions[0], dimensions[1]);
            assertTrue((long)result.width()*result.height() <= PreviewSize.PIXELS);
            assertTrue(result.width() <= dimensions[0] && result.height() <= dimensions[1]);
            assertEquals((double)dimensions[0]/dimensions[1], (double)result.width()/result.height(), .003);
        }
    }
    @Test public void extremeDimensionsCannotOverflowOrMakeZeroSizedPreview() {
        for (int[] dimensions : new int[][]{{Integer.MAX_VALUE,Integer.MAX_VALUE},{Integer.MAX_VALUE,1},{1,Integer.MAX_VALUE}}) {
            var result=PreviewSize.fit(dimensions[0],dimensions[1]);
            assertTrue(result.width()>0 && result.height()>0);
            assertTrue((long)result.width()*result.height()<=PreviewSize.PIXELS);
        }
        assertThrows(IllegalArgumentException.class,()->PreviewSize.fit(0,10));
        assertThrows(IllegalArgumentException.class,()->PreviewSize.fit(10,-1));
    }
}
