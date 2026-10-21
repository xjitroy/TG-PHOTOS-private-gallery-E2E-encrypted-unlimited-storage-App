package app.televault.domain;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.Assert.*;
public class AiModelActivationTest {
 @Test public void diskFailureKeepsThePreviousModelAndPointer() throws Exception {
  Path dir=Files.createTempDirectory("model-activation");Path old=dir.resolve("previous.gguf"),pointer=dir.resolve("active");Files.writeString(old,"working model");Files.writeString(pointer,"previous");
  try { AiModelActivation.commit(true,true,true,()->{throw new IOException("disk full");},()->Files.delete(old));fail(); } catch(IOException expected) { }
  assertEquals("working model",Files.readString(old));assertEquals("previous",Files.readString(pointer));Files.delete(old);Files.delete(pointer);Files.delete(dir);
 }
 @Test public void staleChoiceAndFailedVerificationNeverWriteOrDelete() throws Exception {
  for(boolean[] state:new boolean[][]{{false,true,true},{true,false,true},{true,true,false}}) {
   try { AiModelActivation.commit(state[0],state[1],state[2],()->fail("pointer changed"),()->fail("old model removed"));fail(); } catch(IllegalStateException expected) { }
  }
 }
 @Test public void retirementObservesADurableSuccessfulPointerFirst() throws Exception {
  Path dir=Files.createTempDirectory("model-activation");Path old=dir.resolve("previous.gguf"),pointer=dir.resolve("active");Files.writeString(old,"old");
  AiModelActivation.commit(true,true,true,()->Files.writeString(pointer,"new"),()->{assertEquals("new",Files.readString(pointer));Files.delete(old);});assertFalse(Files.exists(old));Files.delete(pointer);Files.delete(dir);
 }
}
