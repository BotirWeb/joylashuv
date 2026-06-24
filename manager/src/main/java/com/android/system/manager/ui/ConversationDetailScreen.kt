package com.android.system.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.system.manager.data.SmsMessage
import java.text.SimpleDateFormat
import java.util.*

/**
 * Bitta suhbat ichidagi barcha xabarlar (chat UI).
 * @param phoneNumber Suhbat egasi (telefon raqam)
 * @param messages Xabarlar ro'yxati (date bo'yicha tartiblangan)
 * @param onBackClick Orqaga qaytish
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    phoneNumber: String,
    messages: List<SmsMessage>,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = phoneNumber,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Orqaga"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (messages.isEmpty()) {
            // Bo'sh holat
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Xabarlar mavjud emas",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val groupedMessages = messages.groupBy { formatMessageDate(it.date) }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedMessages.forEach { (dateLabel, messagesForDate) ->
                    item(key = "date_header_$dateLabel") {
                        DateHeader(dateLabel = dateLabel)
                    }
                    items(
                        items = messagesForDate,
                        key = { it.messageKey }
                    ) { message ->
                        MessageBubble(message = message)
                    }
                }
            }
        }
    }
}

/**
 * Bitta xabar bubble (inbox chap tomonda, sent o'ng tomonda).
 */
@Composable
private fun MessageBubble(message: SmsMessage) {
    val isInbox = message.type == 1
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isInbox) Arrangement.Start else Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isInbox) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Xabar matni
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isInbox) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = formatMessageTime(message.date),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.End),
                    color = if (isInbox) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    }
                )
            }
        }
    }
}

/**
 * Suhbatda sana ajratgichini (Bugun / Kecha / sana) ko'rsatadi.
 */
@Composable
private fun DateHeader(dateLabel: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

private fun formatMessageDate(timestamp: Long): String {
    val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val todayCalendar = Calendar.getInstance()

    val sameDay = messageCalendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR)
            && messageCalendar.get(Calendar.DAY_OF_YEAR) == todayCalendar.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Bugun"

    todayCalendar.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = messageCalendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR)
            && messageCalendar.get(Calendar.DAY_OF_YEAR) == todayCalendar.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "Kecha"

    return SimpleDateFormat("d-MMMM yyyy", Locale("uz")).format(Date(timestamp))
}

private fun formatMessageTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
