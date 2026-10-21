package app.televault.smart;
import com.google.ai.edge.litertlm.*;
import java.util.Collections;

/** Java boundary also avoids requiring the whole app to change its Kotlin compiler version. */
final class EmbeddingBridge implements AutoCloseable {
 private final EmbeddingEngine engine;
 EmbeddingBridge(String path,String cache) {
  LiteRtLmJni.INSTANCE.nativeSetMinLogSeverity(3);
  Backend cpu=new Backend.CPU(2,null);
  // Full model on disk, load modalities only when used. Audio weights remain available.
  engine=new EmbeddingEngine(new EmbeddingEngineConfig(path,cpu,cpu,cpu,cache,null,1024,140,ActivationDataType.FLOAT32));
  engine.initialize();
 }
 float[] image(byte[] image) { return engine.computeEmbedding(Collections.singletonList(new InputData.Image(image)),options()).getEmbedding(); }
 float[] text(String text) { return engine.computeEmbedding(Collections.singletonList(new InputData.Text("task: search result | query: "+text)),options()).getEmbedding(); }
 private EmbeddingOptions options() { return new EmbeddingOptions(true,true,256,140); }
 @Override public void close() { engine.close(); }
}
