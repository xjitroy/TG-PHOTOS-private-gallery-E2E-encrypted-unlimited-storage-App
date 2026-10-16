package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceFileVisibilityTest {
 @Test public void excludesGeneratedThumbnailsOnly() {
  assertFalse(DeviceFileVisibility.visible("/storage/emulated/0/DCIM/.thumbnails/1.jpg"));
  assertFalse(DeviceFileVisibility.visible("Pictures/.Thumbnail/cache.jpg"));
  assertTrue(DeviceFileVisibility.visible("Pictures/thumbnail-art.jpg"));
  assertTrue(DeviceFileVisibility.visible("Documents/.private/notes.pdf"));
  assertTrue(DeviceFileVisibility.visible(null));
 }
}
