package com.meshovik.presentation.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.Attachment
import com.meshovik.domain.entity.AttachmentType
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MessageType
import com.meshovik.presentation.MeshViewModel
import com.meshovik.transfer.FileTransferState
import com.meshovik.transfer.FileTransferStatus
import timber.log.Timber
import com.meshovik.domain.entity.MeshDevice
// Цветовая палитра

data class DirectChatScreen(
    private val participantAddress: String,
    private val participantName: String,
    private val meshId: String
) : Screen {
    override val key: String = "DirectChat_$participantAddress"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val viewModel: MeshViewModel = koinScreenModel()
        val navigator = LocalNavigator.currentOrThrow
        val uiState by viewModel.uiState.collectAsState()
        var messageText by remember { mutableStateOf("") }
        var isRecording by remember { mutableStateOf(false) }

        val messagesFlow = remember(participantAddress) {
            viewModel.getMessagesFlowForChat(participantAddress)
        }

        val isAlreadyContact by viewModel.isContactFlow(meshId)
            .collectAsState(initial = false)

        val directMessages by messagesFlow.collectAsState()

        // Лаунчер для выбора изображения из галереи
        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            uri?.let {
                Timber.i("Image selected: $it")
                viewModel.sendImage(participantAddress, it)
            }
        }

        // Лаунчер для выбора файлов
        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                Timber.i("File selected: $it")
                // viewModel.sendFile(participantAddress, it)
            }
        }

        LaunchedEffect(directMessages, participantAddress) {
            Timber.w("CHAT DEBUG: Opened chat with address: $participantAddress")
            Timber.w("CHAT DEBUG: ${directMessages.size} messages")
        }

        Scaffold(
            containerColor = ChatColors.BgDark,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            participantName,
                            color = ChatColors.TextPrimary,
                            fontFamily = SpaceMonoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Back",
                                tint = ChatColors.Primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ChatColors.BgSurface
                    ),
                    actions = {
                        Box{
                            if (!isAlreadyContact) {
                                IconButton(onClick = {
                                    val device = MeshDevice(
                                        id = participantAddress,
                                        name = participantName,
                                        address = participantAddress,
                                        meshId = meshId,
                                        userName = participantName,
                                        wifiDirectAddress = ""
                                    )
                                    viewModel.addToContacts(device)
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.PersonAdd,
                                        contentDescription = "Добавить в контакты",
                                        tint = ChatColors.Primary
                                    )
                                }
                            } else {
                                IconButton(onClick = {
                                    viewModel.removeFromContacts(meshId)
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.PersonRemove,
                                        contentDescription = "Удалить из контактов",
                                        tint = ChatColors.Primary
                                    )
                                }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(ChatColors.BgDark)
            ) {
                // Messages list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    reverseLayout = true,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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

                // Input panel
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ChatColors.BgSurface)
                        .padding(12.dp)
                ) {
                    MessageInputPanel(
                        messageText = messageText,
                        onMessageTextChange = { messageText = it },
                        participantName = participantName,
                        onSendMessage = {
                            if (messageText.isNotBlank()) {
                                viewModel.sendMessage(participantAddress, messageText)
                                messageText = ""
                            }
                        },
                        onImageClick = { imagePickerLauncher.launch(PickVisualMediaRequest()) },
                        onFileClick = { filePickerLauncher.launch("*/*") },
                        onMicClick = {
                            isRecording = !isRecording
                            // viewModel.toggleVoiceRecording(participantAddress)
                        },
                        isRecording = isRecording
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInputPanel(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    participantName: String,
    onSendMessage: () -> Unit,
    onImageClick: () -> Unit,
    onFileClick: () -> Unit,
    onMicClick: () -> Unit,
    isRecording: Boolean
) {
    var attachmentMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Attachment button with dropdown
        Box {
            IconButton(
                onClick = { attachmentMenuExpanded = !attachmentMenuExpanded },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Attach",
                    tint = ChatColors.Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Dropdown menu
            DropdownMenu(
                expanded = attachmentMenuExpanded,
                onDismissRequest = { attachmentMenuExpanded = false },
                containerColor = ChatColors.CardBg,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, ChatColors.DividerColor),
                offset = DpOffset(x = 0.dp, y = (-120).dp)
            ) {
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = null,
                                tint = ChatColors.Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Фото или видео",
                                color = ChatColors.TextPrimary,
                                fontFamily = SpaceMonoFont,
                                fontSize = 13.sp
                            )
                        }
                    },
                    onClick = {
                        attachmentMenuExpanded = false
                        onImageClick()
                    }
                )

                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = null,
                                tint = ChatColors.Primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Файл",
                                color = ChatColors.TextPrimary,
                                fontFamily = SpaceMonoFont,
                                fontSize = 13.sp
                            )
                        }
                    },
                    onClick = {
                        attachmentMenuExpanded = false
                        onFileClick()
                    }
                )
            }
        }

        // Text input field
        OutlinedTextField(
            value = messageText,
            onValueChange = onMessageTextChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp, max = 100.dp),
            placeholder = {
                Text(
                    "Сообщение $participantName...",
                    color = ChatColors.TextSecondary,
                    fontFamily = SpaceMonoFont,
                    fontSize = 13.sp
                )
            },
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00E5FF),
                unfocusedBorderColor = Color(0xFF1A1A1A),
                focusedTextColor = Color(0xFFEAEAEA),
                unfocusedTextColor = Color(0xFFEAEAEA),
                cursorColor = Color(0xFF00E5FF),
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = SpaceMonoFont,
                fontSize = 13.sp
            )
        )

        IconButton(
            onClick = if (messageText.isNotBlank()) onSendMessage else onMicClick,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (messageText.isNotBlank()) ChatColors.Primary else ChatColors.PrimaryDim,
                    shape = RoundedCornerShape(10.dp)
                )
                .clip(RoundedCornerShape(10.dp))
        ) {
            Icon(
                imageVector = if (messageText.isNotBlank()) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                contentDescription = if (messageText.isNotBlank()) "Send" else "Record",
                tint = ChatColors.BgDark,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DirectMessageItem(
    message: MeshMessage,
    localDeviceAddress: String,
    transferState: FileTransferState?,
    participantAddress: String,
    onImageClick: (String) -> Unit
) {
    val isFromMe = message.senderId == localDeviceAddress

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isFromMe) ChatColors.MessageBgMe else ChatColors.MessageBgOther
            ),
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isFromMe) 16.dp else 4.dp,
                bottomEnd = if (isFromMe) 4.dp else 16.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                when {
                    // Message with image attachment
                    message.type == MessageType.ATTACHMENT &&
                            message.attachment?.type == AttachmentType.IMAGE -> {
                        ImageAttachmentContent(
                            attachment = message.attachment,
                            transferState = transferState,
                            participantAddress = participantAddress,
                            onImageClick = onImageClick
                        )
                        if (message.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = message.content,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontFamily = SpaceMonoFont,
                                    fontSize = 13.sp,
                                    color = ChatColors.TextPrimary
                                )
                            )
                        }
                    }

                    // Regular text message
                    else -> {
                        Text(
                            text = message.content,
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = SpaceMonoFont,
                                fontSize = 13.sp,
                                color = ChatColors.TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = MeshUtils.formatTimestamp(message.timestamp),
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = SpaceMonoFont,
                        fontSize = 11.sp,
                        color = ChatColors.TextSecondary
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
fun ImageAttachmentContent(
    attachment: Attachment,
    transferState: FileTransferState?,
    participantAddress: String,
    onImageClick: (String) -> Unit
) {
    val localUri = attachment.localUri
        ?: transferState?.localUri?.takeIf { transferState.status == FileTransferStatus.COMPLETED }
    val isTransferring = transferState?.status == FileTransferStatus.TRANSFERRING ||
            transferState?.status == FileTransferStatus.PENDING

    val isFailed = transferState?.status == FileTransferStatus.FAILED

    Timber.i("ИЗОБРАЖЕНИЕ В ДИРЕКТЧАТЕ: $localUri $isFailed $isTransferring ${transferState?.status == FileTransferStatus.COMPLETED}")

    Box(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .heightIn(min = 120.dp, max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ChatColors.CardBg)
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
                    Text(
                        "🖼",
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 48.sp
                        ),
                        color = ChatColors.TextSecondary
                    )
                }
                if (transferState?.isFinished == false) {
                    TransferProgressOverlay(transferState)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "${attachment.fileName} (${formatFileSize(attachment.sizeBytes)})",
        style = androidx.compose.ui.text.TextStyle(
            fontFamily = SpaceMonoFont,
            fontSize = 11.sp,
            color = ChatColors.TextSecondary
        )
    )
}

@Composable
private fun ThumbnailWithProgress(
    attachment: Attachment,
    transferState: FileTransferState
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TransferProgressOverlay(transferState)
    }
}

@Composable
private fun TransferProgressOverlay(transferState: FileTransferState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = ChatColors.Primary,
                    trackColor = ChatColors.CardBg
                )
                Text(
                    text = "${(transferState.progress * 100).toInt()}%",
                    color = ChatColors.Primary,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = SpaceMonoFont,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            } else {
                CircularProgressIndicator(color = ChatColors.Primary)
                Text(
                    text = if (transferState.isSender) "Отправка..." else "Получение...",
                    color = ChatColors.TextPrimary,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = SpaceMonoFont,
                        fontSize = 12.sp
                    )
                )
            }
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