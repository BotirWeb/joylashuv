package com.android.system.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.system.manager.data.SmsConversation
import java.text.SimpleDateFormat
import java.util.*

/**
 * SMS suhbatlar ro'yxati (Telegram/WhatsApp kabi).
 * @param conversations Suhbatlar ro'yxati (lastTimestamp bo'yicha tartiblangan)
 * @param onConversationClick Suhbat bosilganda chaqiriladi
 */
@Composable
fun ConversationListScreen(
    conversations: List<SmsConversation>,
    onConversationClick: (SmsConversation) -> Unit,
    modifier: Modifier = Modifier
) {
    if (conversations.isEmpty()) {
        // Bo'sh holat
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SMS mavjud emas",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(conversations, key = { it.conversationKey }) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    onClick = { onConversationClick(conversation) }
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * Bitta suhbat elementi.
 */
@Composable
private fun ConversationItem(
    conversation: SmsConversation,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Telefon raqam
            Text(
                text = conversation.phoneNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Oxirgi xabar preview
            Text(
                text = conversation.lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Vaqt
        Text(
            text = formatTimestamp(conversation.lastTimestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

/**
 * Timestamp'ni "21:45" yoki "Kecha" formatiga aylantiradi.
 */
private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 24 * 60 * 60 * 1000 -> {
            // Bugun - faqat vaqt
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        diff < 48 * 60 * 60 * 1000 -> {
            // Kecha
            "Kecha"
        }
        else -> {
            // Sana
            SimpleDateFormat("dd MMM", Locale("uz")).format(Date(timestamp))
        }
    }
}
