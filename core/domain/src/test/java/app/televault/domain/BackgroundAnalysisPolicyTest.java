package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class BackgroundAnalysisPolicyTest {
 @Test public void localOriginalsCanBeIndexedWithoutUnmeteredInternet() {
  assertTrue(BackgroundAnalysisPolicy.allowed(false,true,false));
  assertTrue(BackgroundAnalysisPolicy.allowed(false,false,false));
 }
 @Test public void cloudFetchWaitsForUnmeteredAndBatterySaverPausesBoth() {
  assertTrue(BackgroundAnalysisPolicy.allowed(false,true,true));
  assertTrue(BackgroundAnalysisPolicy.allowed(false,false,true));
  assertTrue(BackgroundAnalysisPolicy.allowed(true,false,false));
  assertTrue(BackgroundAnalysisPolicy.allowed(true,false,true));
 }
 @org.junit.Test public void backgroundWorkNeverCrossesLockOrAccountEpoch() {
  org.junit.Assert.assertFalse(BackgroundAnalysisPolicy.sessionAllowed(false, 1L, 1L, false));
  org.junit.Assert.assertFalse(BackgroundAnalysisPolicy.sessionAllowed(true, 1L, 2L, false));
  org.junit.Assert.assertFalse(BackgroundAnalysisPolicy.sessionAllowed(true, 1L, 1L, true));
  org.junit.Assert.assertTrue(BackgroundAnalysisPolicy.sessionAllowed(true, 1L, 1L, false));
 }
}
