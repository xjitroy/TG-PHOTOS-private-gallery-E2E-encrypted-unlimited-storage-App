package app.televault.domain;

import java.util.Locale;

/** Conservative local-only routing; this is not a guarantee of document recognition. */
public final class IntelligenceResultPolicy {
 private IntelligenceResultPolicy() { }
 public static boolean ownedCaption(String caption, String vault, String object) {
  return ForgottenVaultPolicy.validId(vault) && object != null && object.matches("[0-9a-f]{64}")
   && ("TGDRIVE_V1_ANALYSIS:"+vault+":"+object).equals(caption);
 }
 public static boolean localOnly(String text, String labels, boolean video) {
  // A single video preview cannot certify that the rest of the clip contains no document.
  if (video) return true;
  String value=((text == null ? "" : text)+" "+(labels == null ? "" : labels)).toLowerCase(Locale.ROOT);
  if (value.matches("(?s).*(aadhaar|aadhar|uidai|আধার|आधार|passport|পাসপোর্ট|पासपोर्ट|pan card|income tax|debit|credit card|atm|cvv|bank account|ifsc|identity card|voter|driving licen|document|নথি|কার্ড|दस्तावेज|पहचान).*")) return true;
  if (value.matches("(?s).*\\b[2-9][0-9]{3}[ -]?[0-9]{4}[ -]?[0-9]{4}\\b.*")) return true;
  if (value.toUpperCase(Locale.ROOT).matches("(?s).*\\b[A-Z]{5}[0-9]{4}[A-Z]\\b.*")) return true;
  // Dense text and long digit groups remain local even when OCR misses a card heading.
  return value.trim().split("\\s+").length >= 12 || value.matches("(?s).*[0-9][0-9 -]{11,}[0-9].*");
 }
}
