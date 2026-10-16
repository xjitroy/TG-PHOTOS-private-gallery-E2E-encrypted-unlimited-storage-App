package app.televault.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject

/** Device metadata only. Never stores media bytes, recovery secrets or cloud content keys. */
internal class LocalIndexCache(private val context: Context) {
    private val file get() = AtomicFile(File(context.noBackupFilesDir, "device-index-v1.enc"))
    private val alias = "tgphotos.device-index.v1"
    private val maxBytes = MediaMemory.budget(context, 8, 2)
    fun accessStamp(roots: List<Uri>): String {
        val permissions = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val trackedPermissions = permissions + if (Build.VERSION.SDK_INT >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyList()
        val persisted = runCatching { context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet() }.getOrDefault(emptySet())
        return "${DeviceFileAccess.granted(context)}|" + trackedPermissions.joinToString { permission -> "${ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED}" } + "|" + roots.map { "$it:${it in persisted}" }.sorted().joinToString()
    }
    fun fullAccess(): Boolean {
        if (DeviceFileAccess.granted(context)) return true
        val permissions = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    fun limitedVisualAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return app.televault.domain.DeviceIndexAccess.limitedVisual(DeviceFileAccess.granted(context), granted(Manifest.permission.READ_MEDIA_IMAGES), granted(Manifest.permission.READ_MEDIA_VIDEO), granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (Build.VERSION.SDK_INT >= 28) spec.setUnlockedDeviceRequired(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
    }
    fun read(stamp: String): List<LocalMedia>? = runCatching {
        if (!fullAccess()) return@runCatching null // Selected-photo grants require a fresh provider query.
        val raw = file.openRead().use { input ->
            check(file.baseFile.length() in 28..maxBytes.toLong())
            input.readBytes()
        }
        try {
            val plain = LocalIndexEnvelope.decrypt(raw, key(false))
            try {
                val value = JSONObject(plain.toString(Charsets.UTF_8))
                val age = System.currentTimeMillis() - value.getLong("time")
                if (age !in 0 until 86400000L) {
                    file.delete()
                    return@runCatching null
                }
                if (value.getString("access") != stamp) return@runCatching null
                val rows = value.getJSONArray("files")
                check(rows.length() <= 50000)
                List(rows.length()) { index ->
                    val row = rows.getJSONArray(index)
                    LocalMedia(Uri.parse(row.getString(0)), row.getString(1), row.getString(2), row.getLong(3), row.getLong(4), row.getLong(5), row.getString(6), row.getBoolean(7), row.optString(8).takeIf { it.isNotEmpty() }, row.getInt(9), row.getInt(10), row.getLong(11), row.optBoolean(12, false), row.optLong(13, 0).coerceAtLeast(0))
                }
            } finally {
                plain.fill(0)
            }
        } finally {
            raw.fill(0)
        }
    }.getOrNull()
    fun write(stamp: String, items: List<LocalMedia>) {
        if (!fullAccess() || items.size > 50000) return
        runCatching {
            val rows = JSONArray()
            var encodedBytes = 4096L
            items.forEach { item ->
                val row = JSONArray().put(item.uri.toString()).put(item.name).put(item.mime).put(item.size).put(item.date).put(item.modified).put(item.folder).put(item.trashed).put(item.physicalPath.orEmpty()).put(item.width).put(item.height).put(item.addedAt).put(item.galleryHidden).put(item.durationMs)
                encodedBytes += row.toString().toByteArray().size + 1
                if (encodedBytes > maxBytes) return@runCatching
                rows.put(row)
            }
            val plain = JSONObject().put("time", System.currentTimeMillis()).put("access", stamp).put("files", rows).toString().toByteArray()
            try {
                if (plain.size + 28 > maxBytes) return@runCatching
                val encrypted = LocalIndexEnvelope.encrypt(plain, key(true))
                try {
                    val stream = file.startWrite()
                    try {
                        stream.write(encrypted)
                        file.finishWrite(stream)
                    } catch (e: Exception) {
                        file.failWrite(stream)
                        throw e
                    }
                } finally {
                    encrypted.fill(0)
                }
            } finally {
                plain.fill(0)
            }
        } // A cache/Keystore failure must never prevent a live, permission-checked scan.
    }
}
