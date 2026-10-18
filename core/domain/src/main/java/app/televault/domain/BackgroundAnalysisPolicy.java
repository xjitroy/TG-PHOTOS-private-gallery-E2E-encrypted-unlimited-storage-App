package app.televault.domain;
public final class BackgroundAnalysisPolicy {
 private BackgroundAnalysisPolicy() { }
 public static boolean allowed(boolean powerSave, boolean metered, boolean needsNetwork) {
  return !powerSave && (!needsNetwork || !metered);
 }
}
