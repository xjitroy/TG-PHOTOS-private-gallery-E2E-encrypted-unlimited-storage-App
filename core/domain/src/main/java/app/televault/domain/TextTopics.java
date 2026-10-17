package app.televault.domain;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
/** Literal OCR clues only, not scene/brand recognition or inferred personal facts. */
public final class TextTopics {
 private TextTopics() { }
 private static String normalize(String text) { return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
 private static boolean words(String text,String alternatives) { return Pattern.compile("(?<![\\p{L}\\p{N}])(?:"+alternatives+")(?![\\p{L}\\p{N}])").matcher(text).find(); }
 public static String tags(String text) {
  String body=normalize(text),out="";
  if(words(body,"restaurant|restaurante|cafe|café|bistro|diner|dhaba|menu|kfc|mcdonalds|starbucks|domino.?s|রেস্টুরেন্ট|রেস্তোরাঁ|রেস্তোরাঁয়|মেনু|রেস্তোরাতে|रेस्तरां|भोजनालय|होटेल|مطعم|உணவகம்|రెస్టారెంట్"))out+=" restaurant রেস্টুরেন্ট রেস্তোরাঁ রেস্টুরেন্টে রেস্তোরাঁয় रेस्तरां cafe café food খাবার খাবারের খাবারদোকান ";
  if(words(body,"invoice|receipt|bill|factura|বিল|রসিদ|चालान|فاتورة"))out+=" receipt invoice রসিদ বিল ";
  if(words(body,"ticket|boarding|airport|billete|বিমানবন্দর|টিকিট|टिकट|مطار"))out+=" travel ticket ভ্রমণ টিকিট ";
  return out.trim();
 }
 public static String searchText(String text) { return normalize(UniversalMediaSearch.digits(text))+" "+tags(text); }
}
