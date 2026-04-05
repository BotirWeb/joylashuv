package com.android.system.core.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.android.system.core.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Firebase RTDB dan AES kalitini bir marta oladi va EncryptedSharedPreferences'da saqlaydi.
 * Path: devices/{uid}/cryptoKey/key
 */
class KeyFetchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null) {
                if (BuildConfig.DEBUG) { Log.w(TAG, "KeyFetchWorker: no authenticated user, retrying") }
                return Result.retry()
            }

            val latch = CountDownLatch(1)
            var fetchedKey: String? = null
            var fetchError: Exception? = null

            FirebaseDatabase.getInstance()
                .getReference("devices")
                .child(uid)
                .child("cryptoKey")
                .child("key")
                .get()
                .addOnSuccessListener { snapshot ->
                    fetchedKey = snapshot.getValue(String::class.java)
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    fetchError = e
                    latch.countDown()
                }

            // Firebase callback ni kutish (max 10 soniya)
            latch.await(10, TimeUnit.SECONDS)

            if (fetchError != null) {
                if (BuildConfig.DEBUG) { Log.e(TAG, "KeyFetchWorker: fetch failed: ${fetchError!!.message}") }
                return Result.retry()
            }

            val keyBase64 = fetchedKey
            if (keyBase64.isNullOrBlank()) {
                if (BuildConfig.DEBUG) { Log.w(TAG, "KeyFetchWorker: key not found in Firebase, retrying") }
                return Result.retry()
            }

            // Base64 → ByteArray tekshirish
            val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            if (keyBytes.size != 32) {
                if (BuildConfig.DEBUG) { Log.e(TAG, "KeyFetchWorker: invalid key size: ${keyBytes.size} bytes (expected 32)") }
                return Result.retry()
            }

            // EncryptedSharedPreferences'ga saqlash
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedPrefs = EncryptedSharedPreferences.create(
                applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            encryptedPrefs.edit().putString(KEY_AES, keyBase64).apply()

            if (BuildConfig.DEBUG) { Log.d(TAG, "SYS_CORE: AES key fetched successfully") }
            Result.success()

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "KeyFetchWorker: unexpected error: ${e.message}") }
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SYS_CORE_KEYFETCH"
        const val PREFS_NAME = "crypto_prefs"
        const val KEY_AES = "aes_key"

        /**
         * App startup'da bir marta ishga tushirish
         */
        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<KeyFetchWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
            if (BuildConfig.DEBUG) { Log.d(TAG, "KeyFetchWorker scheduled") }
        }

        /**
         * EncryptedSharedPreferences'dan AES kalitini olish
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
    }
}
