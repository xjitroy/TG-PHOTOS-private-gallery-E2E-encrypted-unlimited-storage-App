package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class VideoPresentationTest {
 @Test public void portraitOnlyAroundNineSixteen(){assertTrue(VideoPresentation.shortLayout(1080,1920,1));assertTrue(VideoPresentation.shortLayout(720,1280,1));assertFalse(VideoPresentation.shortLayout(1920,1080,1));assertFalse(VideoPresentation.shortLayout(1080,1080,1));assertFalse(VideoPresentation.shortLayout(0,1080,1));assertFalse(VideoPresentation.shortLayout(1080,1920,Float.NaN));}
 @Test public void unfinishedMoviesResumeWithoutResettingNearTheEnd(){assertEquals(7200123L,VideoPresentation.resumePosition(7200123L,10800000L,false));assertEquals(9999L,VideoPresentation.resumePosition(9999L,10000L,false));assertEquals(0L,VideoPresentation.resumePosition(9999L,10000L,true));}
 @Test public void invalidOrStaleBookmarksCannotSeekOutsideTheFile(){assertEquals(0L,VideoPresentation.resumePosition(-1,10000,false));assertEquals(0L,VideoPresentation.resumePosition(10001,10000,false));assertEquals(0L,VideoPresentation.resumePosition(100,0,false));}
 @Test public void exactlyThreePlays(){assertTrue(VideoPresentation.replayAfterEnd(1));assertTrue(VideoPresentation.replayAfterEnd(2));assertFalse(VideoPresentation.replayAfterEnd(3));assertFalse(VideoPresentation.replayAfterEnd(4));}
 @Test public void movieSwipesEnterAndLeaveFullscreenWithoutChangingMedia(){
  assertTrue(VideoPresentation.fullscreenAfterSwipe(false,-72,72));
  assertFalse(VideoPresentation.fullscreenAfterSwipe(true,72,72));
  assertTrue(VideoPresentation.fullscreenAfterSwipe(true,-100,72));
  assertFalse(VideoPresentation.fullscreenAfterSwipe(false,100,72));
 }
 @Test public void smallOrInvalidGesturesDoNotToggleThePlayer(){
  for(float delta:new float[]{-71,0,71,Float.NaN,Float.POSITIVE_INFINITY}){
   assertFalse(VideoPresentation.fullscreenAfterSwipe(false,delta,72));
   assertTrue(VideoPresentation.fullscreenAfterSwipe(true,delta,72));
  }
  assertFalse(VideoPresentation.fullscreenAfterSwipe(false,-100,0));
 }
 @Test public void compactMoviesKeepTheirRatioAndLeaveRoomForDetails(){
  assertEquals(150,VideoPresentation.compactHeight(360,780,2.4f),.01f);
  assertEquals(202.5f,VideoPresentation.compactHeight(360,780,16f/9f),.01f);
  assertEquals(192,VideoPresentation.compactHeight(640,320,16f/9f),.01f);
 }
 @Test public void compactLayoutRemainsBoundedOnSmallAndMalformedWindows(){
  assertEquals(48,VideoPresentation.compactHeight(320,80,1),.01f);
  assertEquals(202.5f,VideoPresentation.compactHeight(360,780,Float.NaN),.01f);
  assertEquals(1,VideoPresentation.compactHeight(Float.NaN,780,1),.01f);
 }
 @Test public void portraitFeedCannotRotateEvenWithALandscapeClip(){
  assertFalse(VideoPresentation.landscapePlayback(true,false,true,true));
  assertFalse(VideoPresentation.landscapePlayback(false,true,true,true));
 }
 @Test public void resetOrUnknownFramesCannotRotateTheActivity(){
  assertFalse(VideoPresentation.landscapePlayback(false,false,true,false));
 }
 @Test public void knownMovieStillSupportsExplicitLandscapeAndCompactReturn(){
  assertTrue(VideoPresentation.landscapePlayback(false,false,true,true));
  assertFalse(VideoPresentation.landscapePlayback(false,false,false,true));
 }
}
