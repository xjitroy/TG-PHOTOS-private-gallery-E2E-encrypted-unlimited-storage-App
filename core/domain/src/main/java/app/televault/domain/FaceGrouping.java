package app.televault.domain;
/** Gallery identity decisions never use count or similar-looking pictures as identity proof. */
public final class FaceGrouping {
 private FaceGrouping() { }
 public static boolean automatic(String name, boolean reviewed, boolean self) { return (name==null||name.isBlank()) && !reviewed && !self; }
 public static boolean provisional(String name, boolean reviewed, int uniquePhotos) { return (name==null||name.isBlank()) && !reviewed && uniquePhotos<3; }
 public static boolean compatible(String first, String second) { return first!=null&&!first.isEmpty()&&first.equals(second); }
 public static boolean cooccur(java.util.Set<String> firstIds, java.util.Set<String> secondIds, java.util.Set<String> firstHashes, java.util.Set<String> secondHashes) {
  return !java.util.Collections.disjoint(firstIds,secondIds)||!java.util.Collections.disjoint(firstHashes,secondHashes);
 }
 public static boolean conflictingTags(java.util.Set<String> firstExcluded, java.util.Set<String> secondPositive, java.util.Set<String> secondExcluded, java.util.Set<String> firstPositive) {
  return !java.util.Collections.disjoint(firstExcluded,secondPositive)||!java.util.Collections.disjoint(secondExcluded,firstPositive);
 }
 public static boolean canCombine(float score, boolean samePhoto, boolean rejected, boolean competingName) {
  return Float.isFinite(score)&&score>=.66f&&!samePhoto&&!rejected&&!competingName;
 }
}
