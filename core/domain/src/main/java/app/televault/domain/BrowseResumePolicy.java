package app.televault.domain;
public final class BrowseResumePolicy {
 private BrowseResumePolicy() { }
 public static String tab(String tab) {
  if ("VAULT".equals(tab)) return "GALLERY";
  if ("GALLERY".equals(tab)||"LIBRARY".equals(tab)||"SETTINGS".equals(tab)) return tab;
  return "TIMELINE";
 }
}
