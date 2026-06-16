package com.meshovik.presentation.components

import android.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.Chat
import com.meshovik.domain.entity.ChatType
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.presentation.screens.ChatColors
import com.meshovik.presentation.screens.SpaceMonoFont

/**
 * Drawer content with contacts list.
 * Shows your identity, search, and list of chats/contacts.
 */

@Composable
fun ChatDrawerContent(
    chats: List<Chat>,
    knownDevices: List<MeshDevice>,
    onChatClick: (Chat) -> Unit,
    onDirectChatClick: (MeshDevice) -> Unit,
    onCloseDrawer: () -> Unit,
    localDeviceAddress: String = "",
    localDeviceName: String = "You"
) {
    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp),
        drawerContainerColor = Color(0xFF0A0A0A),
        drawerContentColor = ChatColors.TextPrimary,
        drawerTonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(270.dp)
                .statusBarsPadding()
                .border(
                    BorderStroke(1.dp, Color(0xFF1F1F1F)),
                    RoundedCornerShape(topEnd = 0.dp, bottomEnd = 0.dp)
                )
        ) {
            // Header MESHOVIK
            Text(
                text = "МОЖЖЕВЕЛЬНИК",
                fontFamily = SpaceMonoFont,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.Primary,
                modifier = Modifier
                    .padding(start = 20.dp, top = 20.dp, bottom = 16.dp)
            )

            // My Device
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Color(0xFF111111), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = ChatColors.Primary,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(Modifier.width(6.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Вы · $localDeviceName",
                        fontFamily = SpaceMonoFont,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.TextPrimary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(thickness = 1.dp, color = ChatColors.DividerColor)
            Spacer(Modifier.height(8.dp))


            val broadcastChat = chats.find { it.type == ChatType.BROADCAST }
            if (broadcastChat != null) {
                BroadcastChatDrawerItem(
                    chat = broadcastChat,
                    onClick = {
                        onChatClick(broadcastChat)
                        onCloseDrawer()
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            // КОНТАКТЫ section
            Text(
                text = "КОНТАКТЫ",
                fontFamily = SpaceMonoFont,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.TextSecondary,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(knownDevices) { device ->
                    ContactDrawerItem(
                        device = device,
                        onClick = {
                            onDirectChatClick(device)
                            onCloseDrawer()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BroadcastChatDrawerItem(
    chat: Chat,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
        ) {
            Icon(
                imageVector = Icons.Filled.Radar,
                contentDescription = null,
                tint = ChatColors.Primary,
                modifier = Modifier.padding(9.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = chat.participantName,
                fontFamily = SpaceMonoFont,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.TextPrimary
            )
        }
    }
}

@Composable
private fun ContactDrawerItem(
    device: MeshDevice,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1A1A1A),
            border = BorderStroke(1.dp, Color(0xFF333333))
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = ChatColors.TextPrimary,
                modifier = Modifier.padding(9.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = device.userName.ifBlank { device.name },
                fontFamily = SpaceMonoFont,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.TextPrimary
            )
            Text(
                text = "в сети",   // позже заменим на реальный статус
                fontFamily = SpaceMonoFont,
                fontSize = 12.sp,
                color = Color(0xFF00E5FF)
            )
        }
    }
}