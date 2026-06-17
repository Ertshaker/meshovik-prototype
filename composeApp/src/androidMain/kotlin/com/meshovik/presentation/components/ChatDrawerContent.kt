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
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshovik.core.util.MeshUtils
import com.meshovik.domain.entity.Chat
import com.meshovik.domain.entity.ChatType
import com.meshovik.domain.entity.MeshDevice
import com.meshovik.presentation.screens.ChatColors
import com.meshovik.presentation.screens.SectionHeader
import com.meshovik.presentation.screens.SpaceMonoFont
import kotlinx.datetime.format
import java.time.format.DateTimeFormatter

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
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.oneSideBorder(side = BorderSide.End, strokeWidth = 0.5.dp, color = ChatColors.DividerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(270.dp)
                .statusBarsPadding()

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
                    .background(Color(0x1200E5FF))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x1200E5FF),
                    border = BorderStroke(1.dp, Color(0x6900E5FF))
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
                        text = "Вы · $localDeviceName",
                        fontFamily = SpaceMonoFont,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ChatColors.TextPrimary
                    )
                }
            }

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

            // КОНТАКТЫ section
            SectionHeader(
                label = "КОНТАКТЫ",
                modifier = Modifier.padding(top = 12.dp)
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
                imageVector = Icons.Outlined.CellTower,
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
            if (device.isOnline) {
                Text(
                    text = "в сети",
                    fontFamily = SpaceMonoFont,
                    fontSize = 12.sp,
                    color = Color(0xFF00E5FF)
                )
            } else {
                Text(
                    text = "Был в сети ${MeshUtils.formatTimestamp(device.lastSeen.toEpochMilliseconds())}",
                    fontFamily = SpaceMonoFont,
                    fontSize = 12.sp,
                    color = Color(0x6900E5FF)
                )
            }

        }
    }
}

enum class BorderSide {
    Top, Bottom, Start, End
}

fun Modifier.oneSideBorder(
    side: BorderSide,
    strokeWidth: Dp,
    color: Color
): Modifier = this.drawWithContent {
    // Render the actual component content first
    drawContent()

    // Calculate the stroke width in pixels
    val strokeWidthPx = strokeWidth.toPx()

    // Determine the start and end coordinates based on the selected side
    val (start, end) = when (side) {
        BorderSide.Top -> {
            Offset(0f, strokeWidthPx / 2) to Offset(size.width, strokeWidthPx / 2)
        }
        BorderSide.Bottom -> {
            Offset(0f, size.height - strokeWidthPx / 2) to Offset(size.width, size.height - strokeWidthPx / 2)
        }
        BorderSide.Start -> {
            Offset(strokeWidthPx / 2, 0f) to Offset(strokeWidthPx / 2, size.height)
        }
        BorderSide.End -> {
            Offset(size.width - strokeWidthPx / 2, 0f) to Offset(size.width - strokeWidthPx / 2, size.height)
        }
    }

    // Draw the single border line
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = strokeWidthPx
    )
}