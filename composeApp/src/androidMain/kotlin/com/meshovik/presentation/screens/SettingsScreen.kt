package com.meshovik.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.meshovik.presentation.MeshViewModel

object SettingsScreen : Screen {

    override val key: String = "Settings"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val viewModel: MeshViewModel = koinScreenModel()
        val navigator = LocalNavigator.currentOrThrow
        val localNickname by viewModel.localUserName.collectAsState()
        var nickname by remember { mutableStateOf(localNickname) }

        Scaffold(
            containerColor = ChatColors.BgDark,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Настройки",
                            color = ChatColors.TextPrimary,
                            fontFamily = SpaceMonoFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
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
                    )
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    Column {
                        Text(
                            "Твой никнейм",
                            color = ChatColors.TextPrimary,
                            fontFamily = SpaceMonoFont,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { if (it.length <= 12) nickname = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Введите никнейм (max 12)") },
                            maxLines = 1,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ChatColors.Primary,
                                unfocusedBorderColor = ChatColors.DividerColor,
                                focusedTextColor = ChatColors.TextPrimary,
                                unfocusedTextColor = ChatColors.TextPrimary,
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = SpaceMonoFont,
                                fontSize = 15.sp
                            )
                        )

                        Text(
                            "Максимум 12 символов",
                            color = ChatColors.TextSecondary,
                            fontFamily = SpaceMonoFont,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Button(
                            onClick = { viewModel.updateUserName(nickname)},
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ChatColors.Primary)
                        ) {
                            Text(
                                "Сохранить этот прекрасный ник",
                                color = ChatColors.BgDark,
                                fontFamily = SpaceMonoFont,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                item { HorizontalDivider(color = ChatColors.DividerColor, thickness = 1.dp) }

                // ========== ТЕМА ==========
                item {
                    Column {
                        Text(
                            "Тема приложения",
                            color = ChatColors.TextPrimary,
                            fontFamily = SpaceMonoFont,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FilterChip(
                                selected = true, // TODO: привязать состояние
                                onClick = { },
                                label = { Text("Тёмная") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { },
                                label = { Text("Светлая") }
                            )
                            FilterChip(
                                selected = false,
                                onClick = { },
                                label = { Text("Системная") }
                            )
                        }
                    }
                }

                item { HorizontalDivider(color = ChatColors.DividerColor, thickness = 1.dp) }

                // Другие секции (можно расширять)
                item {
                    SettingsCategory(title = "Уведомления") {
                        Text("Плейсхолдер для настроек уведомлений", color = ChatColors.TextSecondary)
                    }
                }

                item {
                    SettingsCategory(title = "О приложении") {
                        Text(
                            "Meshovik Beta\nВерсия 0.1\nBLE Mesh Messenger\n",
                            color = ChatColors.TextSecondary,
                            fontFamily = SpaceMonoFont,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategory(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            color = ChatColors.TextPrimary,
            fontFamily = SpaceMonoFont,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = ChatColors.CardBg),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}