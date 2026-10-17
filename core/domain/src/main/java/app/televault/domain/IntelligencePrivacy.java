package app.televault.domain;
import java.util.Set;
/** Private-transfer exclusions are monotonic; imported stale AI records never remove them. */
public final class IntelligencePrivacy {
 private IntelligencePrivacy() { }
 public static String identity(String id,String installation) {
  if(!ForgottenVaultPolicy.validId(installation))throw new IllegalArgumentException("Invalid device scope");
  return ForgottenVaultPolicy.validId(id) || id.startsWith("local/")?id:"local/"+installation+"/"+id;
 }
 public static boolean excluded(String id,String hash,Set<String> ids,Set<String> hashes) {
  return id!=null && ids.contains(id) || hash!=null && !hash.isEmpty() && hashes.contains(hash);
 }
 public static boolean ownedPersonCaption(String caption,String vault,String person) {
  if(!ForgottenVaultPolicy.validId(vault) || !ForgottenVaultPolicy.validId(person) || caption==null)return false;
  String prefix="TGDRIVE_V1_PEOPLE:"+vault+":"+person+":";
  return caption.startsWith(prefix) && ForgottenVaultPolicy.validId(caption.substring(prefix.length()));
 }
}
