package app.televault.domain;
import java.util.*;
/** Versioned, bounded 256D retrieval projection; face identity vectors use a separate engine. */
public final class AiVectorSearch {
 public static byte[] quantize(float[] vector) {
  if (vector.length!=256) throw new IllegalArgumentException("256 dimensions required");
  double norm=0;for(float v:vector) { if(!Float.isFinite(v))throw new IllegalArgumentException("Nonfinite embedding");norm+=(double)v*v; }
  if(norm<=0)throw new IllegalArgumentException("Empty embedding");norm=Math.sqrt(norm);
  byte[] result=new byte[256];for(int i=0;i<256;i++)result[i]=(byte)Math.max(-127,Math.min(127,Math.round(127*vector[i]/norm)));
  return result;
 }
 public static float score(byte[] document,float[] query) {
  if(document.length!=256 || query.length!=256)return Float.NaN;
  double dot=0,a=0,b=0;
  for(int i=0;i<256;i++) { if(!Float.isFinite(query[i]))return Float.NaN;double v=document[i];dot+=v*query[i];a+=v*v;b+=(double)query[i]*query[i]; }
  if(a<=0 || b<=0)return Float.NaN;
  return (float)Math.max(-1,Math.min(1,dot/Math.sqrt(a*b)));
 }
 public static Map<String,Float> shortlist(Map<String,Float> scores) {
  float top=0.30f;for(float score:scores.values())if(Float.isFinite(score))top=Math.max(top,score);
  final float floor=Math.max(0.30f,top-0.08f);
  Map<String,Float> output=new LinkedHashMap<>();
  scores.entrySet().stream().filter(e->Float.isFinite(e.getValue()) && e.getValue()>=floor).sorted(Map.Entry.<String,Float>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(100).forEach(e->output.put(e.getKey(),e.getValue()));
  return output;
 }
 private AiVectorSearch() { }
}
