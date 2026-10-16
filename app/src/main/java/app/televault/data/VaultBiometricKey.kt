package app.televault.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Opt-in, per-use biometric-bound wrap of the Vault master, never its password. No PIN fallback. */
internal class VaultBiometricKey(private val directory: File, val identity: String) {
    private val file = AtomicFile(File(directory, "biometric.wrap"))
    private val prefix = "tgphotos.private.biometric." + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + "."
    private val aad = "TGPhotos/private-vault/biometric/v1/$identity".toByteArray()
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun descriptor(): JSONObject {
        check(file.baseFile.length() in 1..4096)
        return JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).also {
            check(it.getInt("version") == 1 && it.getString("alias").startsWith(prefix))
            UUID.fromString(it.getString("alias").removePrefix(prefix))
        }
    }
    fun available(): Boolean = runCatching { store().containsAlias(descriptor().getString("alias")) }.getOrDefault(false)
    fun erase() {
        val alias = runCatching { descriptor().getString("alias") }.getOrNull()
        if (alias != null) store().deleteEntry(alias)
        file.delete()
    }
    fun enrollment(master: ByteArray, epoch: Long): Request {
        check(master.size == 32)
        val alias = prefix + UUID.randomUUID()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) else spec.setUserAuthenticationValidityDurationSeconds(-1)
        try {
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(aad)
            }
            return Request(cipher, epoch, true, alias, master.clone())
        } catch (failure: Exception) {
            runCatching { store().deleteEntry(alias) }
            throw failure
        }
    }
    fun unlocking(epoch: Long): Request {
        val data = descriptor()
        val alias = data.getString("alias")
        val bytes = Base64.decode(data.getString("wrapped"), Base64.NO_WRAP)
        try {
            check(bytes.size == 60)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, store().getKey(alias, null), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(aad)
            }
            return Request(cipher, epoch, false, alias, bytes.copyOfRange(12, bytes.size))
        } finally {
            bytes.fill(0)
        }
    }
    inner class Request internal constructor(val cipher: Cipher, val epoch: Long, val enrolling: Boolean, private val alias: String, private val secret: ByteArray) : AutoCloseable {
        val scope: String get() = identity
        private var complete = false
        private var closed = false

        @Synchronized fun finish(authenticated: Cipher): ByteArray? {
            check(!closed && !complete && authenticated === cipher)
            // The hardware-bound cipher itself authorizes the operation, not a success Boolean.
            val result = cipher.doFinal(secret)
            try {
                if (enrolling) {
                    check(result.size == 48)
                    val previous = runCatching { descriptor().getString("alias") }.getOrNull()
                    val bytes = JSONObject().put("version", 1).put("alias", alias).put("wrapped", Base64.encodeToString(cipher.iv + result, Base64.NO_WRAP)).toString().toByteArray()
                    val output = file.startWrite()
                    try {
                        output.write(bytes)
                        file.finishWrite(output)
                    } catch (failure: Exception) {
                        file.failWrite(output)
                        throw failure
                    } finally {
                        bytes.fill(0)
                    }
                    complete = true
                    if (previous != null && previous != alias) runCatching { store().deleteEntry(previous) }
                    return null
                }
                check(result.size == 32)
                complete = true
                return result.clone()
            } finally {
                result.fill(0)
                secret.fill(0)
            }
        }

        @Synchronized override fun close() {
            if (closed) return
            closed = true
            secret.fill(0)
            if (enrolling && !complete) runCatching { store().deleteEntry(alias) }
        }
    }
}
