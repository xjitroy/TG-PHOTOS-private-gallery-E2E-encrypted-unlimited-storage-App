package app.televault.domain;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
/** Stable singleton event identity within one account/Drive; no cipher or schema change. */
public final class ProfileIdentity {
 private ProfileIdentity() {}
 public static String id(long account,String vault) {
  if(account<=0 || vault==null || vault.isEmpty()) throw new IllegalArgumentException("Invalid profile scope");
  return UUID.nameUUIDFromBytes(("TGPhotos/profile/v1/"+account+"/"+vault).getBytes(StandardCharsets.UTF_8)).toString();
 }
}
