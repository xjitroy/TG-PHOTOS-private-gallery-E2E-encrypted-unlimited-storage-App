package app.televault.domain;
/** Charging overrides conservation; an unknown reading postpones speculative analysis. */
public final class BatteryWorkPolicy {
 private BatteryWorkPolicy() { }
 public static boolean backup(int percent, boolean charging) { return charging || percent >= 15; }
 public static boolean analysis(int percent, boolean charging) { return charging || percent >= 25; }
}
