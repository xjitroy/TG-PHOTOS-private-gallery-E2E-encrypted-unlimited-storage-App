package app.televault.domain;
public final class BackgroundAnalysisPolicy {
 public static boolean sessionAllowed(boolean unlocked, long expectedEpoch, long currentEpoch, boolean interactivePaused) { return unlocked && expectedEpoch == currentEpoch && !interactivePaused; }
 private BackgroundAnalysisPolicy() { }
 public static boolean allowed(boolean powerSave, boolean metered, boolean needsNetwork) {
  // Analysis is explicitly exempt from app battery/network pauses; OS scheduling still applies.
  return true;
 }
}
