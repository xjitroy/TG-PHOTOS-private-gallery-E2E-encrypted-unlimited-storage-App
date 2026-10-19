package app.televault.domain;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ExploreMediaTest {
 @Test public void deckContainsEachVideoOnceAndKeepsStableSessionOrder() { List<String> input=Arrays.asList("a","b","a","c","d");List<String> shuffled=ExploreMedia.deck(input,x->x,42);assertEquals(4,shuffled.size());assertEquals(new HashSet<>(input),new HashSet<>(shuffled));assertEquals(shuffled,ExploreMedia.deck(input,x->x,42));assertEquals(Arrays.asList("a","b","a","c","d"),input); }
 @Test public void differentSessionsCanDiscoverDifferentOrders() { List<String> input=Arrays.asList("a","b","c","d","e","f");assertNotEquals(ExploreMedia.deck(input,x->x,42),ExploreMedia.deck(input,x->x,99));assertTrue(ExploreMedia.deck(Collections.<String>emptyList(),x->x,1).isEmpty()); }
 @Test public void shortsExcludeLongPortraitsMoviesAndUnknownGeometry() { assertTrue(ExploreMedia.shortVideo(1080,1920,180000,false));assertFalse(ExploreMedia.shortVideo(1080,1920,180001,false));assertFalse(ExploreMedia.shortVideo(1080,1920,1000,true));assertFalse(ExploreMedia.shortVideo(0,0,1000,false));assertFalse(ExploreMedia.shortVideo(1920,1080,1000,false)); }
}
