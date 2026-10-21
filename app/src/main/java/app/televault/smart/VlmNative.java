package app.televault.smart;
import android.graphics.Bitmap;
/** Called only from the private inference process; buffers never become plaintext disk files. */
final class VlmNative {
 static { System.loadLibrary("tgsmart"); }
 static native long open(String model,String projector,String backend);
 static native byte[] read(long handle,Bitmap image,String request);
 static native void close(long handle);
 private VlmNative() { }
}
