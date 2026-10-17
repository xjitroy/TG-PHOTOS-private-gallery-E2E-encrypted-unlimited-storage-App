package app.televault.domain;
/** Persisted content signatures, never array identity, decide whether a cloud face scan is current. */
public final class FaceScanCache {
 private FaceScanCache() { }
 public static boolean current(String previous,String content,boolean cloud) {
  return content!=null && !content.isEmpty() && previous!=null && (previous.equals(content) || cloud && previous.startsWith(content+":"));
 }
}
