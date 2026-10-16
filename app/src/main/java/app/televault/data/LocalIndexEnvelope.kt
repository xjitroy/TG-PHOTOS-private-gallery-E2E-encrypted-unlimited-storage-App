package app.televault.data

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated local-index envelope; this key never encrypts cloud content. */
internal object LocalIndexEnvelope {
    private val aad = "TGPhotos/device-index/v1".toByteArray()
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }
    fun decrypt(raw: ByteArray, key: SecretKey): ByteArray {
        require(raw.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(raw, 12, raw.size - 12)
    }
}
