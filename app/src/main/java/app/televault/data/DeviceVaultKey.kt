package app.televault.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Convenience/background wrap. Recovery passphrase is the portable recovery mechanism. */
internal class DeviceVaultKey(private val context: Context) {
    private fun key(alias: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create) { "Device key unavailable; unlock with recovery passphrase" }
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (Build.VERSION.SDK_INT >= 28) builder.setUnlockedDeviceRequired(true)
        fun generate() = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(builder.build()) }.generateKey()
        if (Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            builder.setIsStrongBoxBacked(true)
            try {
                return generate()
            } catch (_: StrongBoxUnavailableException) {
                builder.setIsStrongBoxBacked(false)
            }
        }
        return generate()
    }
    private fun file(account: Long, vault: String) = AtomicFile(File(context.noBackupFilesDir, "vault-$account-$vault.wrap"))
    private fun descriptor(account: Long, vault: String): JSONObject? = runCatching {
        val bytes = file(account, vault).openRead().use { it.readBytes() }
        if (bytes.size == 60) null else JSONObject(bytes.toString(Charsets.UTF_8)).also { check(it.getInt("version") == 2) }
    }.getOrNull()
    private fun alias(account: Long, vault: String) = descriptor(account, vault)?.getString("alias") ?: "tgdrive.vault.$account.$vault"
    fun available(account: Long, vault: String): Boolean = runCatching {
        file(account, vault).baseFile.isFile && KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias(account, vault))
    }.getOrDefault(false)

    @Synchronized fun save(account: Long, vault: String, master: ByteArray) {
        saveWrapped(account, vault, master, false)
    }

    /** Explicit destruction only; ordinary locking/password changes retain compatible wrappers. */
    @Synchronized fun erase(account: Long, vault: String) {
        if (!file(account, vault).baseFile.exists()) return
        val name = alias(account, vault)
        try {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(name)) deleteEntry(name)
            }
        } finally {
            file(account, vault).delete()
        }
    }

    @Synchronized fun rotate(account: Long, vault: String, master: ByteArray) {
        saveWrapped(account, vault, master, true)
    }
    private fun saveWrapped(account: Long, vault: String, master: ByteArray, force: Boolean) {
        check(master.size == 32)
        val existing = descriptor(account, vault)
        val previous = alias(account, vault)
        val now = System.currentTimeMillis()
        val rotate = force || existing == null || now < existing.optLong("created") || now - existing.optLong("created") >= 3600000
        val next = if (rotate) "tgdrive.vault.$account.$vault.${UUID.randomUUID()}" else previous
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(next, true))
        cipher.updateAAD("TGDrive/device/$account/$vault".toByteArray())
        val wrapped = cipher.iv + cipher.doFinal(master)
        val data = JSONObject().put("version", 2).put("alias", next).put("created", if (rotate) now else existing!!.getLong("created")).put("wrapped", android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP)).toString().toByteArray()
        val envelope = file(account, vault)
        val stream = envelope.startWrite()
        try {
            stream.write(data)
            envelope.finishWrite(stream)
        } catch (e: Exception) {
            envelope.failWrite(stream)
            if (rotate) runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(next) }
            throw e
        } finally {
            wrapped.fill(0)
            data.fill(0)
        }
        if (next != previous) runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(previous) }
    }

    @Synchronized fun load(account: Long, vault: String): ByteArray {
        val raw = file(account, vault).openRead().use { it.readBytes().also { bytes -> check(bytes.size in 60..4096) } }
        val descriptor = if (raw.size == 60) null else JSONObject(raw.toString(Charsets.UTF_8))
        val encoded = if (descriptor == null) raw else android.util.Base64.decode(descriptor.getString("wrapped"), android.util.Base64.NO_WRAP)
        try {
            check(encoded.size == 60)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(descriptor?.getString("alias") ?: "tgdrive.vault.$account.$vault", false), GCMParameterSpec(128, encoded.copyOfRange(0, 12)))
            cipher.updateAAD("TGDrive/device/$account/$vault".toByteArray())
            val master = cipher.doFinal(encoded, 12, encoded.size - 12)
            check(master.size == 32)
            // Rotate on access after an hour; never require a process/timer to stay alive.
            // Maintenance must not invalidate an already authenticated key. AtomicFile preserves
            // the old envelope if the device cannot rotate/write; recovery credentials stay unchanged.
            val created = descriptor?.optLong("created") ?: 0L
            if (descriptor == null || System.currentTimeMillis() - created >= 3600000 || System.currentTimeMillis() < created) {
                runCatching { saveWrapped(account, vault, master, false) }
            }
            return master
        } finally {
            raw.fill(0)
            encoded.fill(0)
        }
    }
}
