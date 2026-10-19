package app.televault.domain;
import java.util.*;
import java.util.function.Function;
public final class ExploreMedia {
 private ExploreMedia() { }
 public static boolean shortVideo(int width,int height,long duration,boolean movie) {
  return !movie && VideoPresentation.shortLayout(width,height,1f) && (duration<=0 || duration<=180000);
 }
 public static <T> List<T> deck(List<T> entries, Function<T,String> identity, long seed) {
  LinkedHashMap<String,T> unique=new LinkedHashMap<>();
  for(T item:entries) unique.putIfAbsent(identity.apply(item),item);
  ArrayList<T> result=new ArrayList<>(unique.values());
  Collections.shuffle(result,new Random(seed));
  return Collections.unmodifiableList(result);
 }
}
