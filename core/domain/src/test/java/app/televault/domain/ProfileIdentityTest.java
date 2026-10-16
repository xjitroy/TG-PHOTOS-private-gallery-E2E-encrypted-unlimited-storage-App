package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProfileIdentityTest {
 @Test public void profileCannotMergeAcrossAccountsOrDrives() {
  String id=ProfileIdentity.id(42,"drive-a");
  assertEquals(id,ProfileIdentity.id(42,"drive-a"));
  assertNotEquals(id,ProfileIdentity.id(43,"drive-a"));
  assertNotEquals(id,ProfileIdentity.id(42,"drive-b"));
  assertEquals(id,java.util.UUID.fromString(id).toString());
 }
 @Test public void undiscoveredScopeIsRejected() {
  assertThrows(IllegalArgumentException.class,()->ProfileIdentity.id(0,"drive"));
  assertThrows(IllegalArgumentException.class,()->ProfileIdentity.id(42,""));
  assertThrows(IllegalArgumentException.class,()->ProfileIdentity.id(42,null));
 }
}
