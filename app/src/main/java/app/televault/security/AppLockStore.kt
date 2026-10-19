package app.televault.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.televault.domain.AppLockPolicy
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Small device-only authenticated receipt, separate from every media/session/master key. */
internal class AppLockStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "app-lock-grant-v1"))
    private var serial = 0L

    @Synchronized fun generation() = serial

    // Revocation precedes the locked UI. Delete tiny receipt files, never keys or media.
    @Synchronized fun revoke() {
        serial++
        file.delete()
    }
    private fun day() = LocalDate.now().toString() + "@" + ZoneId.systemDefault().id
    private fun boot() = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
    private fun key(create: Boolean): SecretKey {
        val name = "tgphotos.app-lock.receipt.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(name)) return store.getKey(name, null) as SecretKey
        check(create) { "App-lock receipt unavailable" }
        val parameters = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (Build.VERSION.SDK_INT >= 28) parameters.setUnlockedDeviceRequired(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(parameters.build()) }.generateKey()
    }
    private fun aad(scope: String) = (context.packageName + "/app-lock/v1/" + scope).toByteArray()
    suspend fun permits(scope: String, mode: Int): Boolean = withContext(Dispatchers.IO) {
        val token = generation()
        val bytes = synchronized(this@AppLockStore) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext false
            runCatching {
                file.openRead().use {
                    check(it.channel.size() in 29..2048)
                    it.readBytes()
                }
            }.getOrNull()
        } ?: return@withContext false
        var plain: ByteArray? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad(scope))
            plain = cipher.doFinal(bytes, 12, bytes.size - 12)
            val value = JSONObject(plain.toString(Charsets.UTF_8))
            val receipt = AppLockPolicy.Receipt(value.getInt("mode"), value.getLong("wall"), value.getLong("elapsed"), value.getInt("boot"), value.getString("day"))
            synchronized(this@AppLockStore) { token == serial && AppLockPolicy.permits(mode, receipt, System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot(), day()) }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        } finally {
            bytes.fill(0)
            plain?.fill(0)
        }
    }
    suspend fun save(scope: String, mode: Int, token: Long, wall: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()) = withContext(Dispatchers.IO) {
        if (token != generation()) return@withContext
        val plain = JSONObject().put("mode", mode).put("wall", wall).put("elapsed", elapsed).put("boot", boot()).put("day", day()).toString().toByteArray()
        var bytes: ByteArray? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            cipher.updateAAD(aad(scope))
            val sealed = cipher.iv + cipher.doFinal(plain)
            bytes = sealed
            synchronized(this@AppLockStore) {
                if (token != serial) return@synchronized
                val stream = file.startWrite()
                try {
                    stream.write(sealed)
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    throw e
                }
            }
        } finally {
            plain.fill(0)
            bytes?.fill(0)
        }
    }
}
