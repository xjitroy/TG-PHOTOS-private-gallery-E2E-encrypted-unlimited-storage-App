package app.televault.domain;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ViewerOrderTest {
 @Test public void reelsNavigatePhotosAndVideosInTheOriginalAlbumOrder() {
  List<String> media=Arrays.asList("photo-new","reel","photo-next","movie-old");
  assertEquals("photo-next",ViewerOrder.adjacent(media,1,1));
  assertEquals("photo-new",ViewerOrder.adjacent(media,1,-1));
  assertEquals("movie-old",ViewerOrder.adjacent(media,2,1));
  assertNull(ViewerOrder.adjacent(media,0,-1));
  assertNull(ViewerOrder.adjacent(media,3,1));
 }
 @Test public void preservesFolderOrderAndSelectedItem() {
  assertEquals(Arrays.asList("new","selected","old"),ViewerOrder.ensureSelected(Arrays.asList("new","selected","old","selected"),"selected",x->x));
 }
 @Test public void missingSelectionCannotSilentlyOpenAnUnrelatedSearchResult() {
  assertEquals(Arrays.asList("folder-match","name-match"),ViewerOrder.ensureSelected(Arrays.asList("name-match"),"folder-match",x->x));
  assertEquals(Collections.singletonList("selected"),ViewerOrder.ensureSelected(Collections.emptyList(),"selected",x->x));
 }
}
