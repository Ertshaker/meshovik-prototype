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
import com.meshovik.presentation.components.ChatDrawerContent
import com.meshovik.presentation.components.ScanningButton
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

object ChatListScreen : Screen {

    override val key: String = "ChatList"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val viewModel: MeshViewModel = koinViewModel()
        val navigator = LocalNavigator.currentOrThrow
        val uiState by viewModel.uiState.collectAsState()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        // Start advertising and scanning on launch
        LaunchedEffect(Unit) {
            viewModel.startMeshService()
            viewModel.startScanning()
        }

        // Build chat list from devices + broadcast
        val chatList = remember(uiState.devices) {
            buildList {
                add(Chat.createBroadcastChat())
                uiState.devices.forEach { device ->
                    add(Chat.fromDevice(device))
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ChatDrawerContent(
                    chats = chatList,
                    isScanning = uiState.isScanning,
                    onChatClick = { chat ->
                        when (chat.type) {
                            ChatType.BROADCAST -> {
                                navigator.push(BroadcastChatScreen)
                            }
                            ChatType.DIRECT -> {
                                chat.participantAddress?.let { address ->
                                    navigator.push(DirectChatScreen(address, chat.participantName))
                                }
                            }
                        }
                    },
                    onScanningToggle = {
                        if (uiState.isScanning) viewModel.stopScanning()
                        else viewModel.startScanning()
                    },
                    onCloseDrawer = {
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Meshovik") },
                        navigationIcon = {
                            IconButton(onClick = {
                                scope.launch { drawerState.open() }
                            }) {
                                Text("☰", style = MaterialTheme.typography.titleLarge)
                            }
                        }
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📡",
                                style = MaterialTheme.typography.displayLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No devices found")
                            Spacer(modifier = Modifier.height(8.dp))
                            ScanningButton(
                                isScanning = uiState.isScanning,
                                onClick = {
                                    if (uiState.isScanning) viewModel.stopScanning()
                                    else viewModel.startScanning()
                                },
                                modifier = Modifier.width(200.dp)
                            )
                        }
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
                                            navigator.push(BroadcastChatScreen)
                                        }
                                        ChatType.DIRECT -> {
                                            chat.participantAddress?.let { address ->
                                                navigator.push(DirectChatScreen(address, chat.participantName))
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
    HorizontalDivider()
}
