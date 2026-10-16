package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class CloudExportFilterTest {
    @Test public void typeAndCategoryAndHalfOpenWindowAllApply() {
        assertTrue(CloudExportFilter.matches("image/jpeg",1,1,1,100,100,200));
        assertFalse(CloudExportFilter.matches("video/mp4",1,1,1,100,100,200));
        assertFalse(CloudExportFilter.matches("image/jpeg",4,1,1,100,100,200));
        assertFalse(CloudExportFilter.matches("image/jpeg",1,1,1,200,100,200));
    }
    @Test public void allIncludesHiddenAndUnknownButRangedUnknownTimeIsExcluded() {
        assertTrue(CloudExportFilter.matches("application/pdf",0,0,0,0,0,0));
        assertTrue(CloudExportFilter.matches("image/png",4,0,0,100,0,0));
        assertFalse(CloudExportFilter.matches("image/jpeg",1,0,0,0,1,200));
    }
}
