package com.meshovik.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.Chat
import com.meshovik.domain.entity.ChatType

/**
 * Drawer content with chat list and scanning button.
 */
@Composable
fun ChatDrawerContent(
    chats: List<Chat>,
    isScanning: Boolean,
    onChatClick: (Chat) -> Unit,
    onScanningToggle: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column {
            // Header
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Meshovik",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "BLE Mesh Messenger",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    )
                }
            }

            // Chat list
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Broadcast chat always first
                val broadcastChat = chats.find { it.type == ChatType.BROADCAST }
                if (broadcastChat != null) {
                    item {
                        DrawerChatItem(
                            chat = broadcastChat,
                            onClick = {
                                onChatClick(broadcastChat)
                                onCloseDrawer()
                            }
                        )
                        HorizontalDivider()
                    }
                }

                // Direct chats
                val directChats = chats.filter { it.type == ChatType.DIRECT }
                if (directChats.isNotEmpty()) {
                    items(directChats) { chat ->
                        DrawerChatItem(
                            chat = chat,
                            onClick = {
                                onChatClick(chat)
                                onCloseDrawer()
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }

            // Scanning button at bottom
            Surface(
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    ScanningButton(
                        isScanning = isScanning,
                        onClick = {
                            onScanningToggle()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerChatItem(
    chat: Chat,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (chat.type == ChatType.BROADCAST) "📡" else "👤",
            style = MaterialTheme.typography.headlineSmall
        )

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = chat.participantName,
                style = MaterialTheme.typography.titleMedium
            )
            if (chat.lastMessage != null) {
                Text(
                    text = chat.lastMessage,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End
        ) {
            if (chat.lastMessageTime != null) {
                Text(
                    text = MeshUtils.formatTimestamp(chat.lastMessageTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (chat.unreadCount > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Text(chat.unreadCount.toString())
                }
            }
        }
    }
}
