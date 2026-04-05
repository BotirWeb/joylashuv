package com.android.system.manager.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.android.system.manager.BuildConfig
import com.google.firebase.database.FirebaseDatabase
import javax.crypto.KeyGenerator

/**
 * AES-256 kalit generatsiya qiladi va Firebase RTDB ga yozadi.
 * Path: devices/{uid}/cryptoKey/
 */
object KeyManager {

    private const val TAG = "SYS_MGR_KEYMGR"
    private const val PREFS_NAME = "manager_crypto_prefs"
    private const val KEY_AES = "aes_key"

    /**
     * Yangi AES-256 kalit generatsiya qiladi va Firebase'ga yuklaydi.
     * @param context Android context
     * @param uid qurilma UID (Firebase path uchun)
     * @param onSuccess muvaffaqiyatli bo'lganda chaqiriladi
     * @param onFailure xato bo'lganda chaqiriladi
     */
    fun generateAndUploadKey(
        context: Context,
        uid: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        try {
            // AES-256 kalit generatsiya
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val secretKey = keyGen.generateKey()
            val keyBytes = secretKey.encoded
            val keyBase64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

            if (BuildConfig.DEBUG) { Log.d(TAG, "AES-256 key generated (${keyBytes.size} bytes)") }

            // Firebase'ga yozish
            val cryptoRef = FirebaseDatabase.getInstance()
                .getReference("devices")
                .child(uid)
                .child("cryptoKey")

            val data = mapOf(
                "key" to keyBase64,
                "algorithm" to "AES/GCM/NoPadding",
                "version" to 1
            )

            cryptoRef.setValue(data)
                .addOnSuccessListener {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "AES key uploaded to Firebase for uid: $uid") }
                    // Manager uchun lokal saqlash
                    saveKeyLocally(context, keyBase64)
                    onSuccess()
                }
                .addOnFailureListener { e ->
                    if (BuildConfig.DEBUG) { Log.e(TAG, "Failed to upload AES key: ${e.message}") }
                    onFailure(e)
                }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "generateAndUploadKey failed: ${e.message}") }
            onFailure(e)
        }
    }

    /**
     * Kalitni EncryptedSharedPreferences'da saqlaydi (manager uchun)
     */
    private fun saveKeyLocally(context: Context, keyBase64: String) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            encryptedPrefs.edit().putString(KEY_AES, keyBase64).apply()
            if (BuildConfig.DEBUG) { Log.d(TAG, "AES key saved locally") }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "saveKeyLocally failed: ${e.message}") }
        }
    }

    /**
     * Lokal saqlangan AES kalitini qaytaradi
     * @return ByteArray (32 byte) yoki null
     */
    fun getAesKey(context: Context): ByteArray? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val keyBase64 = encryptedPrefs.getString(KEY_AES, null) ?: return null
            Base64.decode(keyBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "getAesKey failed: ${e.message}") }
            null
        }
    }

    /**
     * Lokal kalit mavjudligini tekshiradi
     */
    fun hasKey(context: Context): Boolean {
        return getAesKey(context) != null
    }
}
