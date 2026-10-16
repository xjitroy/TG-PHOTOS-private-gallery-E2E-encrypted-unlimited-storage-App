package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class LibraryPathTest {
 @Test public void directoriesRemainNestedWithoutMergingPrefixSiblings(){assertEquals("Internal/DCIM",LibraryPath.child("Internal","Internal/DCIM/Camera"));assertNull(LibraryPath.child("Internal/Download","Internal/Downloads/Movies"));assertNull(LibraryPath.child("Internal/DCIM","Internal/DCIM"));assertEquals("Internal",LibraryPath.parent("Internal/DCIM"));assertEquals("",LibraryPath.parent("Internal"));}
 @Test public void invalidTraversalDoesNotBecomeAWritePath(){for(String s:new String[]{"A/../B","A/./B","A/\0B"}){try{LibraryPath.clean(s);fail();}catch(IllegalArgumentException expected){}}assertEquals("A/B",LibraryPath.clean("/A//B/"));}
 @Test public void commonOfficeAndMediaTypesUseSeparateCloudCategories(){assertEquals("Documents",LibraryPath.category("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));assertEquals("Photos",LibraryPath.category("image/jpeg"));assertEquals("Videos",LibraryPath.category("video/mp4"));assertEquals("Audio",LibraryPath.category("audio/flac"));assertEquals("Other files",LibraryPath.category("application/zip"));}

 @Test public void typeCategorySearchAndRecentCanNeverEscapeCurrentSubtree(){
  assertTrue(LibraryPath.within("Internal/Download","Internal/Download"));
  assertTrue(LibraryPath.within("Internal/Download","Internal/Download/Movies"));
  assertFalse(LibraryPath.within("Internal/Download","Internal/Downloads"));
  assertFalse(LibraryPath.within("Internal/Download","Internal/DCIM/Camera"));
  assertFalse(LibraryPath.within("Internal/Download","Internal"));
  assertTrue(LibraryPath.within("","Internal/DCIM/Camera"));
 }
}
