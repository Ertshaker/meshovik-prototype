package com.meshovik.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.presentation.MeshViewModel
import org.koin.androidx.compose.koinViewModel

object BroadcastChatScreen : Screen {

    override val key: String = "BroadcastChat"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val viewModel: MeshViewModel = koinViewModel()
        val navigator = LocalNavigator.currentOrThrow
        val uiState by viewModel.uiState.collectAsState()
        var messageText by remember { mutableStateOf("") }

        // Get broadcast messages from repository
        val messagesFlow = remember {
            viewModel.getMessagesFlowForChat("broadcast")
        }

        val broadcastMessages by messagesFlow.collectAsState(initial = emptyList())

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Broadcast Chat") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Text("←")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    reverseLayout = true,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(broadcastMessages.reversed()) { message ->
                        BroadcastMessageItem(
                            message = message,
                            localDeviceAddress = uiState.localDeviceAddress,
                            onSenderClick = { senderId ->
                                // senderId is a Mesh ID (e.g. "MeshA1B2C3D4")
                                // Find device by meshId first, fallback to address
                                val device = uiState.devices.find { it.meshId == senderId }
                                    ?: uiState.devices.find { it.address == senderId }
                                if (device != null) {
                                    // Navigate using meshId as chat ID so messages are routed correctly
                                    navigator.push(DirectChatScreen(device.meshId.ifEmpty { device.address }, device.name))
                                }
                            }
                        )
                    }
                }

                MessageInputRow(
                    text = messageText,
                    onTextChange = { messageText = it },
                    onSend = {
                        if (messageText.isNotBlank()) {
                            viewModel.broadcastMessage(messageText)
                            messageText = ""
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun BroadcastMessageItem(
    message: MeshMessage,
    localDeviceAddress: String,
    onSenderClick: (String) -> Unit
) {
    val isFromMe = message.senderId == localDeviceAddress

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isFromMe) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (!isFromMe) {
                    Text(
                        text = message.senderId,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSenderClick(message.senderId) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = MeshUtils.formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageInputRow(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Broadcast message...") },
            maxLines = 3
        )
        Button(
            onClick = onSend,
            enabled = text.isNotBlank()
        ) {
            Text("Send")
        }
    }
}
