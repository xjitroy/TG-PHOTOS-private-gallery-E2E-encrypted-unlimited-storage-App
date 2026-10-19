package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class BrowseResumePolicyTest {
 @Test public void normalTabsResume() { for(String tab:new String[]{"TIMELINE","GALLERY","LIBRARY","SETTINGS"}) assertEquals(tab,BrowseResumePolicy.tab(tab)); }
 @Test public void privateVaultAlwaysFallsBackToSecondTab() { assertEquals("GALLERY",BrowseResumePolicy.tab("VAULT")); }
 @Test public void unknownOldRoutesFallBackSafely() { assertEquals("TIMELINE",BrowseResumePolicy.tab(null));assertEquals("TIMELINE",BrowseResumePolicy.tab("PRIVATE_MEDIA")); }
}
