package com.meshovik.presentation.screens

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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.Chat
import com.meshovik.domain.entity.ChatType
import com.meshovik.presentation.MeshViewModel

/**
 * Chat list screen - main screen showing all chats.
 */
class ChatListScreen(
    private val viewModel: MeshViewModel
) : Screen {

    override val key: String = "ChatList"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uiState by viewModel.uiState.collectAsState()

        // Build chat list from devices + broadcast
        val chatList = remember(uiState.devices) {
            buildList {
                add(Chat.createBroadcastChat())
                uiState.devices.forEach { device ->
                    add(Chat.fromDevice(device))
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Meshovik") }
                )
            }
        ) { padding ->
            if (chatList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No chats yet. Start scanning to find devices.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(chatList) { chat ->
                        ChatListItem(
                            chat = chat,
                            onClick = {
                                when (chat.type) {
                                    ChatType.BROADCAST -> {
                                        navigator.push(BroadcastChatScreen(viewModel))
                                    }
                                    ChatType.DIRECT -> {
                                        chat.participantAddress?.let { address ->
                                            navigator.push(DirectChatScreen(viewModel, address, chat.participantName))
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatListItem(
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
        // Icon
//        Icon(
//            imageVector = if (chat.type == ChatType.BROADCAST) Icons.Default.BroadcastOnPersonal else Icons.Default.Person,
//            contentDescription = null,
//            modifier = Modifier.size(40.dp),
//            tint = if (chat.type == ChatType.BROADCAST) MaterialTheme.colorScheme.primary
//            else MaterialTheme.colorScheme.secondary
//        )

        // Content
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

        // Time and badge
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
    HorizontalDivider()
}
