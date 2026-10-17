package app.televault.domain;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;
public class PeopleMediaFilterTest {
 @Test public void personNeverBroadensToOtherFiles() { assertFalse(PeopleMediaFilter.matches("b",Set.of("a"),Set.of(),false,false,1)); }
 @Test public void aloneRequiresKnownSingleFace() { for(int n:new int[]{-1,0,2,10})assertFalse(PeopleMediaFilter.matches("a",Set.of("a"),Set.of(),false,true,n));assertTrue(PeopleMediaFilter.matches("a",Set.of("a"),Set.of(),false,true,1)); }
 @Test public void togetherRequiresBothPeople() { assertTrue(PeopleMediaFilter.matches("a",Set.of("a","b"),Set.of("a"),true,false,3));assertFalse(PeopleMediaFilter.matches("b",Set.of("a","b"),Set.of("a"),true,false,2)); }
 @Test public void missingCompanionCannotBecomeAllPhotos() { assertFalse(PeopleMediaFilter.matches("a",Set.of("a"),Set.of(),true,false,2)); }
}
