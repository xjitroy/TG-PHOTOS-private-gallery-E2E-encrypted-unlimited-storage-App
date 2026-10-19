package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScrubPreviewQualityTest {
    @Test public void portraitMosaicNeedsMoreDetailThanFitToAvoidUpscalingTheCrop() {
        var tile=ScrubPreviewQuality.cover(4000,3000,540,810,1024L*1024);
        assertEquals(new ScrubPreviewQuality.Size(1080,810),tile);
        assertTrue(ScrubPreviewQuality.fit(4000,3000,540,810,1024L*1024,false).height()<tile.height());
        assertEquals(new ScrubPreviewQuality.Size(320,240),ScrubPreviewQuality.cover(320,240,1080,1080,1024L*1024));
    }
    @Test public void coverIsBoundedForPanoramasLargeDisplaysAndSmallMemoryBudgets() {
        for(int[] source:new int[][]{{8000,6000},{6000,8000},{Integer.MAX_VALUE,1},{1,Integer.MAX_VALUE},{Integer.MAX_VALUE,Integer.MAX_VALUE}}) {
            for(long budget:new long[]{1,393216,1024L*1024}) {
                var target=ScrubPreviewQuality.cover(source[0],source[1],1440,3200,budget);
                assertTrue(target.width()>0 && target.height()>0);
                assertTrue((long)target.width()*target.height()<=budget);
                assertTrue(target.width()<=source[0] && target.height()<=source[1]);
                int sample=ScrubPreviewQuality.sample(source[0],source[1],target);
                assertTrue((long)Math.max(1,source[0]/sample)*Math.max(1,source[1]/sample)<=4L*budget);
            }
        }
    }
    @Test public void coverPreservesQuarterTurnGeometryAndRejectsInvalidBounds() {
        var normal=ScrubPreviewQuality.cover(4000,3000,540,810,1024L*1024);
        var rotated=ScrubPreviewQuality.cover(3000,4000,810,540,1024L*1024);
        assertEquals(normal.width(),rotated.height());assertEquals(normal.height(),rotated.width());
        assertThrows(IllegalArgumentException.class,()->ScrubPreviewQuality.cover(4000,3000,0,810,1024));
    }
    @Test public void localPhotoTargetsRealViewportInsteadOfThumbnailEdge() {
        assertEquals(new ScrubPreviewQuality.Size(1080,810), ScrubPreviewQuality.fit(4000,3000,1080,2240,2L*1024*1024,false));
        assertEquals(new ScrubPreviewQuality.Size(320,240), ScrubPreviewQuality.fit(320,240,1080,2240,2L*1024*1024,false));
    }
    @Test public void neighborsRetainHalfOfScreenFitPixelsBeforeSelection() {
        var full=ScrubPreviewQuality.fit(4000,3000,1080,2240,2L*1024*1024,false);
        var next=ScrubPreviewQuality.fit(4000,3000,1080,2240,2L*1024*1024,true);
        double ratio=(double)next.width()*next.height()/((long)full.width()*full.height());
        assertTrue(ratio>=.49 && ratio<=.5);
    }
    @Test public void hugeAndThinImagesStayInsideRamAndSampleBounds() {
        for(int[] source:new int[][]{{8000,6000},{6000,8000},{Integer.MAX_VALUE,1},{1,Integer.MAX_VALUE},{Integer.MAX_VALUE,Integer.MAX_VALUE}}) {
            var fit=ScrubPreviewQuality.fit(source[0],source[1],1440,3200,786432,false);
            assertTrue(fit.width()>0 && fit.height()>0);
            assertTrue((long)fit.width()*fit.height()<=786432);
            int sample=ScrubPreviewQuality.sample(source[0],source[1],fit);
            assertTrue(sample>0 && (sample&(sample-1))==0);
            long decoded=(long)Math.max(1,source[0]/sample)*Math.max(1,source[1]/sample);
            assertTrue(decoded<=4L*786432);
        }
    }
    @Test public void sampleDoesNotUndershootNeededScreenDetail() {
        var target=ScrubPreviewQuality.fit(8000,6000,1080,2240,2L*1024*1024,false);
        int sample=ScrubPreviewQuality.sample(8000,6000,target);
        assertTrue(8000/sample>=target.width() && 6000/sample>=target.height());
        assertThrows(IllegalArgumentException.class,()->ScrubPreviewQuality.fit(0,20,1080,2240,100,false));
    }
    @Test public void allExifOrientationsHaveConsistentRotationAndMirror() {
        assertEquals(0,ScrubPreviewQuality.rotation(1));assertTrue(ScrubPreviewQuality.mirrored(2));
        assertEquals(180,ScrubPreviewQuality.rotation(3));assertTrue(ScrubPreviewQuality.mirrored(4));
        assertEquals(270,ScrubPreviewQuality.rotation(5));assertTrue(ScrubPreviewQuality.mirrored(5));
        assertEquals(90,ScrubPreviewQuality.rotation(6));assertFalse(ScrubPreviewQuality.mirrored(6));
        assertEquals(90,ScrubPreviewQuality.rotation(7));assertTrue(ScrubPreviewQuality.mirrored(7));
        assertEquals(270,ScrubPreviewQuality.rotation(8));assertFalse(ScrubPreviewQuality.mirrored(8));
    }
}
