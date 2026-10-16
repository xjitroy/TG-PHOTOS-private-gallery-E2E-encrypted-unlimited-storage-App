package app.televault.crypto;
import org.junit.Test;
import static org.junit.Assert.*;
public class NewPasswordPolicyTest {
 @Test public void creationRequiresAllClassesAndRejectsOldPinsAndPatterns(){
  assertTrue(NewPasswordPolicy.accepts("RADHA@123%_abcd"));
  for(String invalid:new String[]{"123456","123456789012","original long recovery password","ABCDEFGHIJK1!","abcdefghijkl1!","Abcdefghijkl!","Abcdefghijkl1","Abc1!","Abcdefg123! space"})assertFalse(invalid,NewPasswordPolicy.accepts(invalid));
  assertTrue(RecoveryPhrasePolicy.accepts("123456"));
 }
 @Test public void generatedSecretsUseSecureRandomAndAlwaysMeetPolicy(){java.util.Set<String> seen=new java.util.HashSet<>();for(int i=0;i<100;i++){String p=NewPasswordPolicy.generate();assertTrue(NewPasswordPolicy.accepts(p));assertEquals(20,p.length());assertTrue(seen.add(p));}}
}
