package app.televault.domain;

/** Local derived reuse only: this is never proof of cloud backup or duplicate content. */
public final class AiAnalysisSource {
 public static boolean same(String id,String stamp,String model,String revision,String savedId,String savedStamp,String savedModel,String savedRevision) {
  return id!=null && !id.isBlank() && id.length()<=4096 && stamp!=null && !stamp.isBlank() && stamp.length()<=8192 && AiModelPolicy.known(model) && revision!=null && revision.matches("[0-9a-f]{40}") && id.equals(savedId) && stamp.equals(savedStamp) && model.equals(savedModel) && revision.equals(savedRevision);
 }
 private AiAnalysisSource() { }
}
