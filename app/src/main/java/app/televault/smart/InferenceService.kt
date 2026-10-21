package app.televault.smart

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Non-exported, separate process: native failures cannot crash the gallery UI. No keys enter it. */
class InferenceService : Service() {
    private val deadline = Executors.newSingleThreadScheduledExecutor()
    private var handle = 0L
    private var embedding: EmbeddingBridge? = null
    private val gate = Any()
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            data.enforceInterface(DESCRIPTOR)
            check(Binder.getCallingUid() == applicationInfo.uid)
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            if (code == STOP) {
                Process.killProcess(Process.myPid())
                return true
            }
            val timeout = deadline.schedule({ Process.killProcess(Process.myPid()) }, 100, TimeUnit.SECONDS)
            try {
                synchronized(gate) {
                    when (code) {
                        OPEN -> {
                            embedding?.close()
                            embedding = null
                            if (handle != 0L) VlmNative.close(handle)
                            handle = 0L
                            val id = data.readString().orEmpty()
                            val spec = SmartCatalog.read(this@InferenceService).single { it.id == id }
                            val folder = File(noBackupFilesDir, "smart-models-v1/$id-${spec.revision}")
                            check(spec.files.all { File(folder, it.path).length() == it.bytes })
                            if (id == "embedding") {
                                embedding = EmbeddingBridge(File(folder, spec.files[0].path).path, File(cacheDir, "embedding-2").apply { mkdirs() }.path)
                                valid(embedding!!.text("photo"))
                                val sample = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                                try {
                                    val stream = java.io.ByteArrayOutputStream()
                                    check(sample.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
                                    val buffer = stream.toByteArray()
                                    try {
                                        valid(embedding!!.image(buffer))
                                    } finally {
                                        buffer.fill(0)
                                    }
                                } finally {
                                    sample.eraseColor(0)
                                    sample.recycle()
                                }
                            } else {
                                for (lib in listOf("ggml-base", "ggml", "llama", "mtmd")) System.loadLibrary(lib)
                                handle = VlmNative.open(File(folder, spec.files[0].path).path, File(folder, spec.files[1].path).path, File(applicationInfo.nativeLibraryDir, "libggml-cpu-android_armv8.0_1.so").path)
                                check(handle != 0L)
                                // Real image prefill/decode verifies the projector as well as the decoder.
                                val image = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                                try {
                                    check(VlmNative.read(handle, image, "Describe this image in a few words.").isNotEmpty())
                                } finally {
                                    image.eraseColor(0)
                                    image.recycle()
                                }
                            }
                            reply!!.writeNoException()
                            reply.writeInt(Process.myPid())
                        }
                        IMAGE -> {
                            val bytes = data.createByteArray() ?: error("Missing image")
                            check(bytes.size <= 512 * 1024)
                            try {
                                val e = embedding
                                if (e != null) {
                                    val vector = e.image(bytes)
                                    valid(vector)
                                    reply!!.writeNoException()
                                    reply.writeFloatArray(vector)
                                    reply.writeByteArray(byteArrayOf())
                                } else {
                                    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }))
                                    try {
                                        check(bitmap.width <= 768 && bitmap.height <= 768)
                                        val result = VlmNative.read(handle, bitmap, "Return only JSON with caption (one sentence describing visible objects and scene) and text (visible printed words, empty if none). Do not invent details or identities.")
                                        reply!!.writeNoException()
                                        reply.writeFloatArray(floatArrayOf())
                                        reply.writeByteArray(result)
                                        result.fill(0)
                                    } finally {
                                        bitmap.eraseColor(0)
                                        bitmap.recycle()
                                    }
                                }
                            } finally {
                                bytes.fill(0)
                            }
                        }
                        TEXT -> {
                            val text = data.readString().orEmpty()
                            check(text.length in 1..2048)
                            val vector = checkNotNull(embedding).text(text)
                            valid(vector)
                            reply!!.writeNoException()
                            reply.writeFloatArray(vector)
                        }
                        else -> return super.onTransact(code, data, reply, flags)
                    }
                }
            } catch (_: OutOfMemoryError) {
                reply?.writeException(IllegalStateException("model-memory"))
            } catch (_: LinkageError) {
                reply?.writeException(IllegalStateException("model-runtime"))
            } catch (_: Exception) {
                reply?.writeException(IllegalStateException("model-request"))
            } finally {
                timeout.cancel(false)
            }
            return true
        }
    }
    private fun valid(vector: FloatArray) {
        check(vector.size == 256 && vector.all { it.isFinite() } && vector.any { it != 0f })
    }
    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
    companion object {
        const val DESCRIPTOR = "app.televault.smart.private-inference-v1"
        const val OPEN = IBinder.FIRST_CALL_TRANSACTION
        const val IMAGE = OPEN + 1
        const val TEXT = OPEN + 2
        const val STOP = OPEN + 3
    }
}
