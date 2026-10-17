package app.televault.domain;
import java.util.Set;
/** Unknown detections never count as an alone portrait; companion selection is an intersection. */
public final class PeopleMediaFilter {
 private PeopleMediaFilter() { }
 public static boolean matches(String id,Set<String> primary,Set<String> companion,boolean withCompanion,boolean alone,int faces) {
  return primary.contains(id) && (!withCompanion || companion.contains(id)) && (!alone || faces==1);
 }
}
