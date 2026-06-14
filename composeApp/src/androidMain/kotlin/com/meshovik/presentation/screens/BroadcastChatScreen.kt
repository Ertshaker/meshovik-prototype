package com.meshovik.presentation.screens

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.toUri
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.Attachment
import com.meshovik.domain.entity.AttachmentType
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MessageType
import com.meshovik.presentation.MeshViewModel
import com.meshovik.transfer.FileTransferState
import com.meshovik.transfer.FileTransferStatus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin
import timber.log.Timber

object BroadcastChatScreen : Screen {

    override val key: String = "BroadcastChat"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val viewModel: MeshViewModel = getKoin().get()
        val navigator = LocalNavigator.currentOrThrow
        val uiState by viewModel.uiState.collectAsState()
        var messageText by remember { mutableStateOf("") }

        // Get broadcast messages from repository
        val messagesFlow = remember {
            viewModel.getMessagesFlowForChat("broadcast")
        }

        val broadcastMessages by messagesFlow.collectAsState(initial = emptyList())

        // Лаунчер для выбора изображения из галереи
        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            uri?.let {
                Timber.i("Image selected for broadcast: $it")
                viewModel.sendImage("BROADCAST", it)
            }
        }

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
                        val transferState = message.attachment?.let {
                            uiState.fileTransfers[it.id]
                        }
                        BroadcastMessageItem(
                            message = message,
                            localDeviceAddress = uiState.localDeviceAddress,
                            transferState = transferState,
                            viewModel = viewModel,
                            onSenderClick = { senderId ->
                                // senderId is a Mesh ID (e.g. "MeshA1B2C3D4")
                                // Find device by meshId first, fallback to address
                                val device = uiState.devices.find { it.meshId == senderId }
                                    ?: uiState.devices.find { it.address == senderId }
                                if (device != null) {
                                    // Navigate using meshId as chat ID so messages are routed correctly
                                    navigator.push(DirectChatScreen(device.meshId.ifEmpty { device.address }, device.name))
                                }
                            },
                            onImageClick = { imageUri ->
                                navigator.push(
                                    FullScreenImageScreen(
                                        imageUri = imageUri,
                                        fileName = message.attachment?.fileName ?: ""
                                    )
                                )
                            }
                        )
                    }
                }

                BroadcastMessageInputRow(
                    text = messageText,
                    onTextChange = { messageText = it },
                    onSend = {
                        if (messageText.isNotBlank()) {
                            viewModel.broadcastMessage(messageText)
                            messageText = ""
                        }
                    },
                    onAttachImage = { imagePickerLauncher.launch(PickVisualMediaRequest()) }
                )
            }
        }
    }
}

@Composable
private fun BroadcastMessageItem(
    message: MeshMessage,
    localDeviceAddress: String,
    transferState: FileTransferState?,
    viewModel: MeshViewModel,
    onSenderClick: (String) -> Unit,
    onImageClick: (String) -> Unit
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
            ),
            modifier = Modifier.widthIn(max = 280.dp)
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

                when {
                    // Сообщение с вложением-изображением
                    message.type == MessageType.ATTACHMENT &&
                    message.attachment?.type == AttachmentType.IMAGE -> {
                        ImageAttachmentContent(
                            attachment = message.attachment,
                            transferState = transferState,
                            participantAddress = "BROADCAST", // Для broadcast не используется, но требуется сигнатурой
                            viewModel = viewModel,
                            onImageClick = onImageClick
                        )
                        // Подпись (если есть)
                        if (message.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Обычное текстовое сообщение
                    else -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = MeshUtils.formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun BroadcastMessageInputRow(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Кнопка прикрепить изображение
        IconButton(
            onClick = onAttachImage
        ) {
            Text(
                text = "🖼",
                style = MaterialTheme.typography.titleLarge
            )
        }

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

// ─── Переиспользуемые компоненты из DirectChatScreen ───────────────────────

@Composable
private fun ImageAttachmentContent(
    attachment: Attachment,
    transferState: FileTransferState?,
    participantAddress: String,
    viewModel: MeshViewModel,
    onImageClick: (String) -> Unit
) {
    val localUri = attachment.localUri ?: transferState?.localUri

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2C2C2C)) // тёмно-серый placeholder
    ) {
        when {
            localUri != null -> {
                AsyncImage(
                    model = localUri.toUri(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onImageClick(localUri) }
                )
            }

            else -> {
                // Серый placeholder пока ничего нет
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("🖼", style = MaterialTheme.typography.displayLarge, color = Color.Gray)
                }
                if (transferState?.isFinished == false) {
                    TransferProgressOverlay(transferState)
                }
            }
        }
    }

    // Имя файла + размер
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "${attachment.fileName} (${formatFileSize(attachment.sizeBytes)})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TransferProgressOverlay(transferState: FileTransferState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            if (transferState.progress > 0f) {
                LinearProgressIndicator(
                    progress = { transferState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White
                )
                Text(
                    text = "${(transferState.progress * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            } else {
                CircularProgressIndicator(color = Color.White)
                Text(
                    text = if (transferState.isSender) "Отправка..." else "Получение...",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun ImagePlaceholder(fileName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "🖼",
                style = MaterialTheme.typography.displaySmall
            )
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
