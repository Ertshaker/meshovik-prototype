package com.meshovik.presentation.screens

import android.graphics.BitmapFactory
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.toCoilUri
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.Attachment
import com.meshovik.domain.entity.AttachmentType
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MessageType
import com.meshovik.presentation.MeshViewModel
import com.meshovik.transfer.FileTransferState
import com.meshovik.transfer.FileTransferStatus
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin
import timber.log.Timber

data class DirectChatScreen(
    private val participantAddress: String,
    private val participantName: String
) : Screen {

    override val key: String = "DirectChat_$participantAddress"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val viewModel: MeshViewModel = koinScreenModel()
        val navigator = LocalNavigator.currentOrThrow
        val uiState by viewModel.uiState.collectAsState()
        var messageText by remember { mutableStateOf("") }

        val messagesFlow = remember(participantAddress) {
            viewModel.getMessagesFlowForChat(participantAddress)
        }

        val directMessages by messagesFlow.collectAsState()

        // Лаунчер для выбора изображения из галереи (современный API)
        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            uri?.let {
                Timber.i("Image selected: $it")
                viewModel.sendImage(participantAddress, it)
            }
        }

        LaunchedEffect(directMessages, participantAddress) {
            Timber.w("CHAT DEBUG: Opened chat with address: $participantAddress")
            Timber.w("CHAT DEBUG: ${directMessages.size} messages")
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(participantName) },
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
                    items(directMessages.reversed()) { message ->
                        val transferState = message.attachment?.let {
                            uiState.fileTransfers[it.id]
                        }
                        DirectMessageItem(
                            message = message,
                            localDeviceAddress = uiState.localDeviceAddress,
                            transferState = transferState,
                            participantAddress = participantAddress,
                            viewModel = viewModel,
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

                // Панель ввода
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Кнопка прикрепить изображение
                    IconButton(
                        onClick = { imagePickerLauncher.launch(PickVisualMediaRequest()) }
                    ) {
                        Text(
                            text = "🖼",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message to $participantName...") },
                        maxLines = 3
                    )
                    Button(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                viewModel.sendMessage(participantAddress, messageText)
                                messageText = ""
                            }
                        },
                        enabled = messageText.isNotBlank()
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectMessageItem(
    message: MeshMessage,
    localDeviceAddress: String,
    transferState: FileTransferState?,
    participantAddress: String,
    viewModel: MeshViewModel,
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
                when {
                    // Сообщение с вложением-изображением
                    message.type == MessageType.ATTACHMENT &&
                    message.attachment?.type == AttachmentType.IMAGE -> {
                        ImageAttachmentContent(
                            attachment = message.attachment,
                            transferState = transferState,
                            participantAddress = participantAddress,
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

/**
 * Контент для сообщения с изображением:
 * - Если файл уже получен (localUri != null) — показываем превью через Coil
 * - Если передача идёт — показываем thumbnail (Base64) + прогресс-бар
 * - Если ожидаем — показываем placeholder
 */
@Composable
private fun ImageAttachmentContent(
    attachment: Attachment,
    transferState: FileTransferState?,
    participantAddress: String,
    viewModel: MeshViewModel,
    onImageClick: (String) -> Unit
) {
    val localUri = attachment.localUri ?: transferState?.localUri

    val isTransferring = transferState?.status == FileTransferStatus.TRANSFERRING ||
            transferState?.status == FileTransferStatus.PENDING

    val isFailed = transferState?.status == FileTransferStatus.FAILED

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
            isTransferring -> {
                ThumbnailWithProgress(
                    attachment = attachment,
                    transferState = transferState
                )
            }

            else -> {
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
private fun ThumbnailWithProgress(
    attachment: Attachment,
    transferState: FileTransferState
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Прогресс поверх
        TransferProgressOverlay(transferState)
    }
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
