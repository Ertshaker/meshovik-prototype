package com.meshovik.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.domain.entity.MeshMessage
import org.koin.androidx.compose.koinViewModel

/**
 * Main mesh messenger screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshMessengerScreen(
    viewModel: MeshViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var messageText by remember { mutableStateOf("") }
    var selectedDevice by remember { mutableStateOf<MeshDevice?>(null) }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MeshEvent.ServiceStarted -> { /* Show toast or snackbar */ }
                is MeshEvent.MessageSent -> { /* Show confirmation */ }
                is MeshEvent.MessageBroadcast -> { /* Show confirmation */ }
                is MeshEvent.Error -> { /* Show error */ }
            }
        }
    }

    // Start mesh service on launch
    LaunchedEffect(Unit) {
        viewModel.startMeshService()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meshovik") },
                actions = {
                    // Scanning toggle
                    IconButton(onClick = {
                        if (uiState.isScanning) viewModel.stopScanning()
                        else viewModel.startScanning()
                    }) {
                        Text(if (uiState.isScanning) "⏹" else "🔍")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status bar
            StatusCard(
                isAdvertising = uiState.isAdvertising,
                isScanning = uiState.isScanning,
                deviceCount = uiState.devices.size
            )

            // Device list
            Text("Discovered Devices (${uiState.devices.size})", style = MaterialTheme.typography.titleMedium)
            DeviceList(
                devices = uiState.devices,
                selectedDevice = selectedDevice,
                onDeviceSelected = { selectedDevice = it }
            )

            // Messages
            Text("Messages", style = MaterialTheme.typography.titleMedium)
            MessageList(
                messages = uiState.receivedMessages + uiState.sentMessages,
                modifier = Modifier.weight(1f)
            )

            // Message input
            MessageInput(
                text = messageText,
                onTextChange = { messageText = it },
                onSend = {
                    if (selectedDevice != null) {
                        viewModel.sendMessage(selectedDevice!!.address, messageText)
                    } else {
                        viewModel.broadcastMessage(messageText)
                    }
                    messageText = ""
                },
                selectedDevice = selectedDevice,
                enabled = messageText.isNotBlank()
            )
        }
    }
}

@Composable
private fun StatusCard(
    isAdvertising: Boolean,
    isScanning: Boolean,
    deviceCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAdvertising) Color(0xFF4CAF50).copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isAdvertising) "● Advertising" else "○ Not advertising",
                color = if (isAdvertising) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isScanning) "● Scanning" else "○ Idle",
                color = if (isScanning) Color(0xFF2196F3) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("$deviceCount devices")
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<MeshDevice>,
    selectedDevice: MeshDevice?,
    onDeviceSelected: (MeshDevice) -> Unit
) {
    if (devices.isEmpty()) {
        Text(
            "No devices found. Start scanning to discover nearby devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(devices) { device ->
                DeviceItem(
                    device = device,
                    isSelected = selectedDevice?.address == device.address,
                    onClick = { onDeviceSelected(device) }
                )
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: MeshDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${MeshUtils.formatAddress(device.address)} • RSSI: ${device.rssi}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                MeshUtils.formatTimestamp(device.lastSeen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: List<MeshMessage>,
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) {
        Text(
            "No messages yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    } else {
        LazyColumn(
            modifier = modifier,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages.reversed()) { message ->
                MessageItem(message)
            }
        }
    }
}

@Composable
private fun MessageItem(message: MeshMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "From: ${MeshUtils.formatAddress(message.senderId)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    MeshUtils.formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(message.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    selectedDevice: MeshDevice?,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    if (selectedDevice != null) "Message to ${selectedDevice.name}"
                    else "Broadcast message"
                )
            },
            maxLines = 3
        )
        Button(
            onClick = onSend,
            enabled = enabled
        ) {
            Text("Send")
        }
    }
}
