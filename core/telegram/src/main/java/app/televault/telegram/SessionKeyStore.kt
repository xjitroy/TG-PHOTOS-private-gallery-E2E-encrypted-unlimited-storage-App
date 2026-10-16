package app.televault.telegram

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps the TDLib DB key in Android Keystore. This is separate from future vault keys. */
internal class SessionKeyStore(private val context: Context) {
    fun loadOrCreate(): ByteArray {
        val envelope = AtomicFile(File(context.noBackupFilesDir, "tdlib-db-key.wrap"))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "televault.tdlib.db.v1"
        val hasEnvelope = envelope.baseFile.exists() || File(envelope.baseFile.path + ".bak").exists()
        // Never silently replace an invalidated/missing wrapping key for an existing session.
        check(!hasEnvelope || store.containsAlias(alias))
        val key = if (store.containsAlias(alias)) {
            store.getKey(alias, null) as SecretKey
        } else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .apply { if (Build.VERSION.SDK_INT >= 28) setUnlockedDeviceRequired(true) }
                        .build()
                )
            }.generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val aad = "TeleVault/TDLib/database-key/v1".toByteArray(Charsets.UTF_8)
        if (hasEnvelope) {
            val wrapped = envelope.openRead().use { input ->
                val bytes = ByteArray(60)
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    check(count > 0)
                    offset += count
                }
                check(input.read() == -1)
                bytes
            }
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
                cipher.updateAAD(aad)
                return cipher.doFinal(wrapped, 12, wrapped.size - 12).also { check(it.size == 32) }
            } finally {
                wrapped.fill(0)
            }
        }
        val databaseDir = File(context.noBackupFilesDir, "tdlib/database")
        check(!databaseDir.exists() || databaseDir.listFiles().isNullOrEmpty())
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val output = envelope.startWrite()
            try {
                output.write(cipher.iv + cipher.doFinal(secret))
                envelope.finishWrite(output)
            } catch (e: Exception) {
                envelope.failWrite(output)
                throw e
            }
            return secret
        } catch (e: Exception) {
            secret.fill(0)
            throw e
        }
    }
}
