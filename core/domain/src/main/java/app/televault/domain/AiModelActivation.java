package app.televault.domain;

/** The durable active-pointer write is the transaction boundary; retirement can never precede it. */
public final class AiModelActivation {
 @FunctionalInterface public interface Action { void run() throws Exception; }
 public static void commit(boolean verified,boolean initialized,boolean stillSelected,Action durablePointer,Action retirePrevious) throws Exception {
  if(!AiModelPolicy.canCommit(verified,verified,initialized,initialized,stillSelected))throw new IllegalStateException("Replacement is not ready");
  durablePointer.run();
  retirePrevious.run();
 }
 private AiModelActivation() { }
}
