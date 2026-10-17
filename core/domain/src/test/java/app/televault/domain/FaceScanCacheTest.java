package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class FaceScanCacheTest {
 @Test public void restoredSameContentSkipsScanAcrossThumbnailIdentityChanges() { assertTrue(FaceScanCache.current("sha:1234","sha",true));assertTrue(FaceScanCache.current("sha:-993","sha",true));assertTrue(FaceScanCache.current("sha","sha",true)); }
 @Test public void newOrChangedContentIsNotSkipped() { assertFalse(FaceScanCache.current(null,"new",true));assertFalse(FaceScanCache.current("old:123","new",true)); }
 @Test public void missingSignatureNeverSkips() { assertFalse(FaceScanCache.current("","",true));assertFalse(FaceScanCache.current("old",null,true)); }
 @Test public void deviceFingerprintsRequireExactMatch() { assertFalse(FaceScanCache.current("uri:size:date","uri",false));assertTrue(FaceScanCache.current("uri:size:date","uri:size:date",false)); }
}
