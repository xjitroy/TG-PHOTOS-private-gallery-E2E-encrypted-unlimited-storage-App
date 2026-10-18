package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class BackgroundAnalysisPolicyTest {
 @Test public void localOriginalsCanBeIndexedWithoutUnmeteredInternet() {
  assertTrue(BackgroundAnalysisPolicy.allowed(false,true,false));
  assertTrue(BackgroundAnalysisPolicy.allowed(false,false,false));
 }
 @Test public void cloudFetchWaitsForUnmeteredAndBatterySaverPausesBoth() {
  assertFalse(BackgroundAnalysisPolicy.allowed(false,true,true));
  assertTrue(BackgroundAnalysisPolicy.allowed(false,false,true));
  assertFalse(BackgroundAnalysisPolicy.allowed(true,false,false));
  assertFalse(BackgroundAnalysisPolicy.allowed(true,false,true));
 }
}
