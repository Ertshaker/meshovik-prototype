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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.AttachmentType
import com.meshovik.domain.entity.MeshMessage
import com.meshovik.domain.entity.MessageType
import com.meshovik.presentation.MeshViewModel
import com.meshovik.transfer.FileTransferState
import timber.log.Timber

public object ChatColors {
    val BgDark = Color(0xFF050505)
    val BgSurface = Color(0xFF090909)
    val Primary = Color(0xFF00E5FF)
    val PrimaryDim = Color(0xFF00B8CC)
    val Secondary = Color(0xFF00FFA3)
    val TextPrimary = Color(0xFFEAEAEA)
    val TextSecondary = Color(0xFFA8A8A8)
    val DividerColor = Color(0xFF1A1A1A)
    val CardBg = Color(0xFF111111)
    val MessageBgMe = Color(0xFF0D3D3F)
    val MessageBorderMe = Color(0xFF156265)
    val MessageBgOther = Color(0xFF1A1A1A)
    val MessageBorderOther = Color(0xFF282828)
}

public val SpaceMonoFont = FontFamily.Monospace

object BroadcastChatScreen : Screen {
    override val key: String = "BroadcastChat"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val viewModel: MeshViewModel = koinScreenModel()
        val navigator = LocalNavigator.currentOrThrow
        val uiState by viewModel.uiState.collectAsState()
        var messageText by remember { mutableStateOf("") }

        val messagesFlow = remember {
            viewModel.getMessagesFlowForChat("broadcast")
        }

        val broadcastMessages by messagesFlow.collectAsState(initial = emptyList())

        val imagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            uri?.let {
                Timber.i("Image selected for broadcast: $it")
                viewModel.sendImage("BROADCAST", it)
            }
        }

        Scaffold(
            containerColor = ChatColors.BgDark,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Всеобщий чат",
                                color = ChatColors.TextPrimary,
                                fontFamily = SpaceMonoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
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
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(ChatColors.BgDark)
            ) {
                // Divider после TopAppBar
                HorizontalDivider(
                    color = ChatColors.DividerColor,
                    thickness = 1.dp
                )

                // Messages List
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    reverseLayout = true,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(broadcastMessages.reversed()) { message ->
                        val transferState = message.attachment?.let { uiState.fileTransfers[it.id] }
                        BroadcastMessageItem(
                            message = message,
                            localDeviceAddress = uiState.localDeviceAddress,
                            transferState = transferState,
                            viewModel = viewModel,
                            onSenderClick = { senderId ->
                                val device = uiState.devices.find { it.meshId == senderId }
                                    ?: uiState.devices.find { it.address == senderId }
                                if (device != null) {
                                    navigator.push(
                                        DirectChatScreen(
                                            device.meshId.ifEmpty { device.address },
                                            device.name
                                        )
                                    )
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

                // Divider перед Input Panel
                HorizontalDivider(
                    color = ChatColors.DividerColor,
                    thickness = 1.dp
                )

                // Input Panel
                BroadcastMessageInputPanel(
                    messageText = messageText,
                    onMessageTextChange = { messageText = it },
                    onSendMessage = {
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
                containerColor = if (isFromMe) ChatColors.MessageBgMe else ChatColors.MessageBgOther
            ),
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isFromMe) 0.dp else 16.dp,   // прямой угол у своих
                bottomEnd = if (isFromMe) 16.dp else 0.dp,
            ),
            border = if (isFromMe) BorderStroke(1.dp, color = ChatColors.MessageBorderMe)
            else BorderStroke(1.dp, color = ChatColors.MessageBorderOther)
        ) {
            Column(modifier = Modifier.padding(12.dp).width(IntrinsicSize.Max)) {
                if (!isFromMe) {
                    Text(
                        text = message.senderId,
                        style = androidx.compose.ui.text.TextStyle(
                            fontFamily = SpaceMonoFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = ChatColors.Primary,
                        modifier = Modifier.clickable { onSenderClick(message.senderId) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                when {
                    message.type == MessageType.ATTACHMENT &&
                            message.attachment?.type == AttachmentType.IMAGE -> {
                        ImageAttachmentContent(
                            attachment = message.attachment,
                            transferState = transferState,
                            participantAddress = "BROADCAST",
                            viewModel = viewModel,
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

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = MeshUtils.formatTimestamp(message.timestamp).substringBeforeLast(":"), // без секунд
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
private fun BroadcastMessageInputPanel(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onAttachImage: () -> Unit
) {
    var attachmentMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.BgSurface)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attachment button
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
                            onAttachImage()
                        }
                    )
                }
            }

            OutlinedTextField(
                value = messageText,
                onValueChange = onMessageTextChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 100.dp),
                placeholder = {
                    Text(
                        "Сообщение...",
                        color = ChatColors.TextSecondary,
                        fontFamily = SpaceMonoFont,
                        fontSize = 13.sp
                    )
                },
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ChatColors.Primary,
                    unfocusedBorderColor = ChatColors.DividerColor,
                    focusedTextColor = ChatColors.TextPrimary,
                    unfocusedTextColor = ChatColors.TextPrimary,
                    cursorColor = ChatColors.Primary,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = SpaceMonoFont,
                    fontSize = 13.sp
                )
            )

            IconButton(
                onClick = if (messageText.isNotBlank()) onSendMessage else onSendMessage,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (messageText.isNotBlank()) ChatColors.Primary else ChatColors.PrimaryDim,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clip(RoundedCornerShape(10.dp))
            ) {
                Icon(
                    imageVector = if (messageText.isNotBlank()) Icons.Filled.Send else Icons.Filled.Mic,
                    contentDescription = if (messageText.isNotBlank()) "Send" else "Record",
                    tint = ChatColors.BgDark,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}