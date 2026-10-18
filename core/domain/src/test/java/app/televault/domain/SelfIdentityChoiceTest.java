package app.televault.domain;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class SelfIdentityChoiceTest {
 private SelfIdentityChoice.Choice c(String id, boolean selected, long time, boolean deleted) { return new SelfIdentityChoice.Choice(id, selected, time, deleted); }
 @Test public void noImplicitIdentityAndLatestExplicitSelectionWins() {
  assertNull(SelfIdentityChoice.current(List.of(c("a",true,0,false))));
  assertEquals("b",SelfIdentityChoice.current(List.of(c("a",true,10,false),c("b",true,20,false))));
 }
 @Test public void clearedOrDeletedChoiceDoesNotResurrectOldIdentity() {
  assertNull(SelfIdentityChoice.current(List.of(c("a",true,10,false),c("b",false,20,false))));
  assertNull(SelfIdentityChoice.current(List.of(c("a",true,10,false),c("b",true,20,true))));
 }
 @Test public void concurrentChoiceIsStableRegardlessOfRestoreOrder() {
  var a=c("a",true,20,false);var b=c("b",true,20,false);
  assertEquals("b",SelfIdentityChoice.current(List.of(a,b)));
  assertEquals("b",SelfIdentityChoice.current(List.of(b,a)));
 }
}
