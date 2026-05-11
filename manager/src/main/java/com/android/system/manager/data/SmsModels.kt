package com.android.system.manager.data

/**
 * SMS suhbati (conversation list uchun).
 * Firebase: devices/{uid}/sms/conversations/{conversationKey}/
 */
data class SmsConversation(
    val conversationKey: String,        // MD5 hash key (conv_xxx)
    val phoneNumber: String,            // Decrypted raqam yoki manzil
    val lastMessage: String,            // Preview (unencrypted, max 50 char)
    val lastTimestamp: Long             // Eng oxirgi xabar vaqti
)

/**
 * SMS xabari (conversation detail uchun).
 * Firebase: .../messages/msg_{timestamp}/
 */
data class SmsMessage(
    val messageKey: String,             // msg_1715025485000
    val body: String,                   // To'liq matn (decrypted)
    val date: Long,
    val type: Int,                      // 1=inbox, 2=sent
    val id: String                      // SMS ID
)

/**
 * Suhbat detali (ekran state).
 */
data class ConversationDetail(
    val phoneNumber: String,            // Header uchun
    val messages: List<SmsMessage>      // Xabarlar ro'yxati
)
