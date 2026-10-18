package app.televault

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object RuntimeDiagnostics {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val data = JSONObject().put("version", BuildConfig.VERSION_NAME).put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("time", System.currentTimeMillis()).put("type", error.javaClass.name)
                    .put("causes", JSONArray(generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(4).map { it.javaClass.name }.toList()))
                    .put("frames", JSONArray(error.stackTrace.take(8).map { "${it.className}#${it.methodName}:${it.lineNumber}" }))
                    .toString().toByteArray()
                val file = AtomicFile(File(context.noBackupFilesDir, "last-runtime-diagnostic.json"))
                val output = file.startWrite()
                try {
                    output.write(data)
                    file.finishWrite(output)
                } catch (failure: Exception) {
                    file.failWrite(output)
                }
            }
            if (previous != null) previous.uncaughtException(thread, error) else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
    fun summary(context: Context): String {
        val exit = if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val manager = context.getSystemService(android.app.ActivityManager::class.java)
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.let { "Android exit reason=${it.reason} status=${it.status}" }.orEmpty()
            }.getOrDefault("")
        } else {
            ""
        }
        return javaSummary(context) + if (exit.isNotEmpty()) " · $exit" else ""
    }

    /** Allowlisted local report: no exception messages, media paths, names, phone numbers or keys. */
    fun report(context: Context): String = buildString {
        append("TG Photos ").append(BuildConfig.VERSION_NAME).append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append(" · Android ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        append(summary(context))
        runCatching {
            val file = AtomicFile(File(context.noBackupFilesDir, "last-runtime-diagnostic.json"))
            val data = file.openRead().use { input ->
                val bytes = ByteArray(4097)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read <= 0) break
                    count += read
                }
                check(count in 1..4096)
                JSONObject(String(bytes, 0, count, Charsets.UTF_8))
            }
            for (field in listOf("causes", "frames")) {
                val values = data.optJSONArray(field) ?: continue
                for (i in 0 until minOf(values.length(), 8)) {
                    val text = values.optString(i)
                    if (text.length <= 256 && text.matches(Regex("[A-Za-z0-9_.$#:<>()-]+"))) append('\n').append(text)
                }
            }
        }
    }
    private fun javaSummary(context: Context): String = runCatching {
        val file = AtomicFile(File(context.noBackupFilesDir, "last-runtime-diagnostic.json"))
        file.openRead().use { input ->
            val bytes = ByteArray(4097)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) break
                count += read
            }
            check(count <= 4096)
            val data = JSONObject(String(bytes, 0, count, Charsets.UTF_8))
            "${data.optString("version")} · Android ${data.optInt("sdk")} · ${data.optString("type").substringAfterLast('.')} · ${data.optJSONArray("frames")?.optString(0).orEmpty()}"
        }
    }.getOrDefault("No recorded Java crash")
}
