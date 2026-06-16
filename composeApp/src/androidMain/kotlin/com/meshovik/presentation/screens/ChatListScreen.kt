package com.meshovik.presentation.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.Chat
import com.meshovik.domain.entity.ChatType
import com.meshovik.presentation.MeshViewModel
import com.meshovik.presentation.components.ChatDrawerContent
import com.meshovik.presentation.components.ScanningButton
import kotlinx.coroutines.launch

// Цветовая палитра
private object ChatListColors {
    val BgDark = Color(0xFF050505)
    val BgSurface = Color(0xFF090909)
    val Primary = Color(0xFF00E5FF)
    val PrimaryDim = Color(0xFF00B8CC)
    val Secondary = Color(0xFF00FFA3)
    val TextPrimary = Color(0xFFEAEAEA)
    val TextSecondary = Color(0xFFA8A8A8)
    val DividerColor = Color(0xFF1A1A1A)
    val CardBg = Color(0xFF111111)
}

private val SpaceMonoFont1 = FontFamily.Monospace

object ChatListScreen : Screen {
    override val key: String = "ChatList"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun Content() {
        val viewModel: MeshViewModel = koinScreenModel()
        val navigator = LocalNavigator.currentOrThrow
        val uiState by viewModel.uiState.collectAsState()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val isMeshActive by viewModel.isMeshServiceActive.collectAsState()
        val deviceName = viewModel.localUserName.collectAsState()
        // Build chat list from devices + broadcast
        val chatList = remember(uiState.devices) {
            buildList {
                // Broadcast ВСЕГДА должен быть первым
                add(Chat.createBroadcastChat())

                // Прямые чаты из устройств
                uiState.devices
                    .filter { device ->
                        device.userName.isNotBlank() &&
                                device.userName != "Unknown" &&
                                device.meshId.isNotBlank()
                    }
                    .forEach { device ->
                        add(Chat.fromDevice(device))
                    }
            }
        }

        var menuExpanded by remember { mutableStateOf(false) }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ChatDrawerContent(
                    chats = chatList,
                    knownDevices = viewModel.contacts.value,
                    onChatClick = { chat ->
                        when (chat.type) {
                            ChatType.BROADCAST -> {
                                navigator.push(BroadcastChatScreen)
                            }
                            ChatType.DIRECT -> {
                                navigator.push(
                                    DirectChatScreen(
                                        participantAddress = chat.id,
                                        participantName = chat.participantName,
                                        meshId = chat.participantMeshId!!
                                    )
                                )
                            }
                        }
                    },
                    onDirectChatClick = { device ->
                        // Переход в DirectChat из контактов
                        navigator.push(
                            DirectChatScreen(
                                participantAddress = device.meshId.ifBlank { device.address },
                                participantName = device.userName.ifBlank { device.name },
                                meshId = device.meshId
                            )
                        )
                    },
                    onCloseDrawer = {
                        scope.launch { drawerState.close() }
                    },
                    localDeviceAddress = uiState.localDeviceAddress,
                    localDeviceName = deviceName.value
                )
            }
        ) {
            Scaffold(
                containerColor = ChatListColors.BgDark,
                topBar = {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "МОЖЖЕВЕЛЬНИК",
                                    color = ChatListColors.Primary,
                                    fontFamily = SpaceMonoFont1,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .background(
                                                color = if (isMeshActive) ChatListColors.Secondary else Color(0xFF555555),
                                                shape = CircleShape
                                            )
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = ChatListColors.BgSurface
                        ),
                        actions = {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = null,
                                        tint = ChatListColors.Primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                    containerColor = ChatListColors.CardBg,
                                    shape = RoundedCornerShape(12.dp),
                                    tonalElevation = 0.dp,
                                    border = BorderStroke(1.dp, ChatListColors.DividerColor),
                                    offset = DpOffset(x = (-12).dp, y = (-42).dp)
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Settings,
                                                    contentDescription = null,
                                                    tint = ChatListColors.Primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Text(
                                                    "Настройки",
                                                    color = ChatListColors.TextPrimary,
                                                    fontFamily = com.meshovik.presentation.screens.SpaceMonoFont1,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            navigator.push(SettingsScreen)
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (uiState.isScanning)
                                                        Icons.Filled.BluetoothDisabled
                                                    else
                                                        Icons.Filled.BluetoothSearching,
                                                    contentDescription = null,
                                                    tint = ChatListColors.Primary,
                                                    modifier = Modifier.size(20.dp)
                                                )

                                                Text(
                                                    if (uiState.isScanning)
                                                        "Остановить Bluetooth сервис"
                                                    else
                                                        "Включить Bluetooth сервис",
                                                    color = ChatListColors.TextPrimary,
                                                    fontFamily = com.meshovik.presentation.screens.SpaceMonoFont1,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            if (uiState.isScanning) viewModel.stopMeshService() else viewModel.startMeshService()
                                        }
                                    )
                                }
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
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Text(
                                text = "📡",
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 64.sp
                                ),
                                color = ChatListColors.Primary
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                "Устройства пока не найдены...",
                                color = ChatListColors.TextSecondary,
                                fontFamily = com.meshovik.presentation.screens.SpaceMonoFont1,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            ScanningButton(
                                isScanning = uiState.isScanning,
                                onClick = {},
                                modifier = Modifier.width(200.dp)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .background(ChatListColors.BgDark),
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
                                            chat.participantAddress.let { address ->
                                                navigator.push(DirectChatScreen(address, chat.participantName, chat.participantMeshId!!))
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
    val isBroadcast = chat.type == ChatType.BROADCAST

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar / icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = if (isBroadcast) ChatListColors.PrimaryDim else ChatListColors.CardBg,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isBroadcast) "📡" else "👤",
                style = androidx.compose.ui.text.TextStyle(fontSize = 24.sp)
            )
        }

        // Chat info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = chat.participantName,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = com.meshovik.presentation.screens.SpaceMonoFont1,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ChatListColors.TextPrimary
                )
            )

            if (chat.lastMessage != null) {
                Text(
                    text = chat.lastMessage,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.meshovik.presentation.screens.SpaceMonoFont1,
                        fontSize = 12.sp,
                        color = ChatListColors.TextSecondary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Time + badge
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (chat.lastMessageTime != null) {
                Text(
                    text = MeshUtils.formatTimestamp(chat.lastMessageTime),
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.meshovik.presentation.screens.SpaceMonoFont1,
                        fontSize = 11.sp,
                        color = ChatListColors.TextSecondary
                    )
                )
            }

            if (chat.unreadCount > 0) {
                Badge(
                    containerColor = ChatListColors.Primary,
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(
                        chat.unreadCount.toString(),
                        color = ChatListColors.BgDark,
                        style = androidx.compose.ui.text.TextStyle(
                            fontFamily = com.meshovik.presentation.screens.SpaceMonoFont1,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }

    HorizontalDivider(
        color = ChatListColors.DividerColor,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}