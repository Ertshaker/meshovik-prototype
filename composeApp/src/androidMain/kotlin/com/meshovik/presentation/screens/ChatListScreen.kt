package com.meshovik.presentation.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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


private object C {
    val Bg          = Color(0xFF050505)
    val Surface     = Color(0xFF090909)
    val Surface2    = Color(0xFF0A0E10)
    val Card        = Color(0xFF111111)
    val Primary     = Color(0xFF00E5FF)
    val PrimaryDim  = Color(0xFF00B8CC)
    val Secondary   = Color(0xFF00FFA3)
    val TextPri     = Color(0xFFEAEAEA)
    val TextSec     = Color(0xFFA8A8A8)
    val TextMuted   = Color(0xFF888888)
    val Divider     = Color(0xFF444444)
    val DividerDark = Color(0xFF0D0D0D)
    val OnlineDot   = Color(0xFF00E5FF)
    val OfflineDot  = Color(0xFF2A2A2A)
    val AccentBg    = Color(0x1200E5FF)
    val AccentBorder= Color(0x3000E5FF)
}

private val MonoFont = FontFamily.Monospace

// ─── Screen ──────────────────────────────────────────────────────────────────
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

        val chatList = remember(uiState.devices) {
            buildList {
                add(Chat.createBroadcastChat())
                uiState.devices
                    .filter { it.userName.isNotBlank() && it.userName != "Unknown" && it.meshId.isNotBlank() }
                    .forEach { add(Chat.fromDevice(it)) }
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
                            ChatType.BROADCAST -> navigator.push(BroadcastChatScreen)
                            ChatType.DIRECT -> navigator.push(
                                DirectChatScreen(
                                    participantAddress = chat.id,
                                    participantName = chat.participantName,
                                    meshId = chat.participantMeshId!!
                                )
                            )
                        }
                    },
                    onDirectChatClick = { device ->
                        navigator.push(
                            DirectChatScreen(
                                participantAddress = device.meshId.ifBlank { device.address },
                                participantName = device.userName.ifBlank { device.name },
                                meshId = device.meshId
                            )
                        )
                    },
                    onCloseDrawer = { scope.launch { drawerState.close() } },
                    localDeviceAddress = uiState.localDeviceAddress,
                    localDeviceName = deviceName.value
                )
            }
        ) {
            Scaffold(
                containerColor = C.Bg,
                topBar = {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(
                                        imageVector = Icons.Filled.Menu,
                                        contentDescription = "Меню",
                                        tint = C.Primary,
                                    )
                                }
                                Text(
                                    text = "МОЖЖЕВЕЛЬНИК",
                                    color = C.Primary,
                                    fontFamily = MonoFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.padding(horizontal = 5.dp))
                                // Статус-точка mesh-сервиса
                                StatusPulse(active = isMeshActive)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = C.Surface
                        ),
                        actions = {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = "Меню",
                                        tint = C.Primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                MeshDropdownMenu(
                                    expanded = menuExpanded,
                                    isScanning = uiState.isScanning,
                                    onDismiss = { menuExpanded = false },
                                    onSettings = {
                                        menuExpanded = false
                                        navigator.push(SettingsScreen)
                                    },
                                    onToggleBluetooth = {
                                        menuExpanded = false
                                        if (uiState.isScanning) viewModel.stopMeshService()
                                        else viewModel.startMeshService()
                                    }
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                if (chatList.isEmpty()) {
                    EmptyState(
                        modifier = Modifier.padding(padding),
                        isScanning = uiState.isScanning,
                        onScanToggle = {}
                    )
                } else {
                    ChatList(
                        modifier = Modifier.padding(padding),
                        chatList = chatList,
                        onChatClick = { chat ->
                            when (chat.type) {
                                ChatType.BROADCAST -> navigator.push(BroadcastChatScreen)
                                ChatType.DIRECT -> chat.participantAddress.let { addr ->
                                    navigator.push(DirectChatScreen(addr, chat.participantName, chat.participantMeshId!!))
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// ─── Список чатов ────────────────────────────────────────────────────────────
@Composable
private fun ChatList(
    modifier: Modifier = Modifier,
    chatList: List<Chat>,
    onChatClick: (Chat) -> Unit
) {
    val broadcast = chatList.filter { it.type == ChatType.BROADCAST }
    val devices   = chatList.filter { it.type == ChatType.DIRECT }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(C.Bg),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(broadcast) { chat ->
            BroadcastCard(chat = chat, onClick = { onChatClick(chat) })
        }

        item {
            SectionHeader(
                label = "НАЙДЕННЫЙ УСТРОЙСТВА",
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        if (devices.isNotEmpty()) {
            items(devices) { chat ->
                DeviceItem(chat = chat, onClick = { onChatClick(chat) })
            }
        }
    }
}

// ─── Broadcast-карточка (акцентная) ─────────────────────────────────────────
@Composable
private fun BroadcastCard(chat: Chat, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface2)
            .then(
                Modifier.border(
                    width = 1.dp,
                    color = C.AccentBorder,
                    shape = RoundedCornerShape(14.dp)
                )
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Иконка с пульсирующим зелёным бейджем
        Box(modifier = Modifier.size(44.dp)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(13.dp),
                color = C.AccentBg,
                border = BorderStroke(0.5.dp, C.AccentBorder)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CellTower,
                    contentDescription = null,
                    tint = C.Primary,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(9.dp)
                )
            }
            // Зелёный онлайн-бейдж
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .background(C.Secondary, CircleShape)
                    .border(1.5.dp, C.Bg, CircleShape)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.participantName,
                fontFamily = MonoFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = C.TextPri
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (chat.lastMessageTime != null) {
                Text(
                    text = MeshUtils.formatTimestamp(chat.lastMessageTime),
                    fontSize = 9.sp,
                    color = C.TextMuted
                )
            }
            if (chat.unreadCount > 0) {
                UnreadBadge(count = chat.unreadCount)
            }
        }
    }
}

// ─── Device-айтем ────────────────────────────────────────────────────────────
@Composable
private fun DeviceItem(chat: Chat, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Аватар + онлайн-точка
            Box(modifier = Modifier.size(40.dp)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(12.dp),
                    color = C.AccentBg,
                    border = BorderStroke(0.5.dp, C.AccentBorder)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhoneAndroid,
                        contentDescription = null,
                        tint = C.TextPri,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(7.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(9.dp)
                        .background(C.OnlineDot, CircleShape)
                        .border(1.5.dp, C.Bg, CircleShape)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.participantName,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = C.TextPri
                )
                val sub = chat.lastMessage ?: "в сети · BLE"
                Text(
                    text = sub,
                    fontSize = 10.sp,
                    color = if (chat.lastMessage != null) C.TextSec else Color(0x00E5FF66),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (chat.lastMessageTime != null) {
                    Text(
                        text = MeshUtils.formatTimestamp(chat.lastMessageTime),
                        fontSize = 9.sp,
                        color = C.TextMuted
                    )
                }
                if (chat.unreadCount > 0) {
                    UnreadBadge(count = chat.unreadCount)
                }
            }
        }

        HorizontalDivider(
            color = C.DividerDark,
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = 68.dp)
        )
    }
}

// ─── Вспомогательные компоненты ──────────────────────────────────────────────

@Composable
public fun SectionHeader(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
            color = C.TextMuted,
            fontWeight = FontWeight.Medium
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = C.Divider,
            thickness = 1.dp
        )
    }
}

@Composable
fun StatusPulse(active: Boolean) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (active) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(1200)),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                color = if (active) C.Secondary.copy(alpha = alpha) else C.OfflineDot,
                shape = CircleShape
            )
    )
}

@Composable
private fun UnreadBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = C.Primary
    ) {
        Text(
            text = count.toString(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = C.Bg,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun MeshDropdownMenu(
    expanded: Boolean,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onToggleBluetooth: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        border = BorderStroke(0.5.dp, C.Divider),
        offset = DpOffset(x = (-12).dp, y = (-42).dp)
    ) {
        DropdownMenuItem(
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = C.Primary, modifier = Modifier.size(18.dp))
                    Text("Настройки", color = C.TextPri, fontFamily = MonoFont, fontSize = 13.sp)
                }
            },
            onClick = onSettings
        )
        DropdownMenuItem(
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Filled.BluetoothDisabled else Icons.Filled.BluetoothSearching,
                        contentDescription = null,
                        tint = C.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isScanning) "Остановить Bluetooth сервис" else "Включить Bluetooth сервис",
                        color = C.TextPri,
                        fontFamily = MonoFont,
                        fontSize = 13.sp
                    )
                }
            },
            onClick = onToggleBluetooth
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    isScanning: Boolean,
    onScanToggle: () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.CellTower,
                contentDescription = null,
                tint = C.Primary.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Устройства не найдены",
                color = C.TextSec,
                fontFamily = MonoFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Убедитесь, что BLE включён и рядом есть устройства",
                color = C.TextMuted,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.width(220.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            ScanningButton(
                isScanning = isScanning,
                onClick = onScanToggle,
                modifier = Modifier.width(200.dp)
            )
        }
    }
}