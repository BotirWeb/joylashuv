package com.android.system.core.crypto

import android.util.Base64
import android.util.Log
import com.android.system.core.BuildConfig
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM shifrlash/deshifrlash.
 * Format: Base64(IV) + "." + Base64(ciphertext)
 */
object CryptoManager {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 12   // 96 bit
    private const val TAG_SIZE = 128 // GCM auth tag bits
    private const val TAG = "SYS_CORE_CRYPTO"

    /**
     * Matnni shifrlaydi.
     * @param plaintext shifrlash kerak bo'lgan matn
     * @param keyBytes AES-256 kalit (32 byte)
     * @return "Base64(IV).Base64(ciphertext)" yoki null (xato bo'lsa)
     */
    fun encrypt(plaintext: String, keyBytes: ByteArray): String? {
        return try {
            val iv = ByteArray(IV_SIZE).also {
                java.security.SecureRandom().nextBytes(it)
            }
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            "$ivB64.$ctB64"
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "encrypt failed: ${e.message}") }
            null
        }
    }

    /**
     * Shifrlangan matnni deshifrlaydi.
     * @param encrypted "Base64(IV).Base64(ciphertext)" formatidagi matn
     * @param keyBytes AES-256 kalit (32 byte)
     * @return deshifrlangan matn yoki null (xato bo'lsa)
     */
    fun decrypt(encrypted: String, keyBytes: ByteArray): String? {
        return try {
            val parts = encrypted.split(".")
            if (parts.size != 2) {
                if (BuildConfig.DEBUG) { Log.e(TAG, "decrypt: invalid format (expected IV.ciphertext)") }
                return null
            }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE, iv))
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "decrypt failed: ${e.message}") }
            null
        }
    }
}
