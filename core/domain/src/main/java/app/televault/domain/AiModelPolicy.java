package app.televault.domain;

/** Physical RAM guides first-run selection; current available memory still guards inference. */
public final class AiModelPolicy {
 public static final long GIB = 1024L * 1024 * 1024;
 public static String automatic(long totalBytes) {
  if (totalBytes > 0 && totalBytes <= 3 * GIB) return "small";
  if (totalBytes > 12 * GIB) return "qwen";
  // Advertised 8GB devices usually expose around 7.2GiB to Android.
  if (totalBytes >= 7 * GIB) return "embedding";
  return "balanced";
 }
 public static boolean known(String id) { return id != null && (id.equals("small") || id.equals("balanced") || id.equals("qwen") || id.equals("embedding")); }
 public static String desired(String choice, long ram, boolean fallback) { return fallback ? "small" : known(choice) ? choice : automatic(ram); }
 public static boolean canCommit(boolean sizes, boolean hashes, boolean initialized, boolean smoke, boolean stillSelected) { return sizes && hashes && initialized && smoke && stillSelected; }
 public static boolean enoughMemory(long available, long threshold, String id) {
  long needed = "small".equals(id) ? 450L * 1024 * 1024 : "embedding".equals(id) ? 900L * 1024 * 1024 : "qwen".equals(id) ? 1500L * 1024 * 1024 : 1100L * 1024 * 1024;
  return available - Math.max(0,threshold) >= needed;
 }
 private AiModelPolicy() { }
}
