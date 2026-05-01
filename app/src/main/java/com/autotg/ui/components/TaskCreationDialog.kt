package com.autotg.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCreationDialog(
    bots: List<Bot>,
    chats: List<Chat>,
    onDismiss: () -> Unit,
    onConfirm: (botId: Long, chatId: Long, content: String, time: Long) -> Unit
) {
    var selectedBot by remember { mutableStateOf<Bot?>(bots.firstOrNull()) }
    var selectedChat by remember { mutableStateOf<Chat?>(chats.firstOrNull()) }
    var content by remember { mutableStateOf("") }
    var timeString by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + 600000)))
    }

    var botExpanded by remember { mutableStateOf(false) }
    var chatExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Scheduled Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Bot Selection
                Box {
                    OutlinedTextField(
                        value = selectedBot?.name ?: "Select Bot",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Bot") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = botExpanded) }
                    )
                    Box(modifier = Modifier.matchParentSize().clickable { botExpanded = true })
                    DropdownMenu(expanded = botExpanded, onDismissRequest = { botExpanded = false }) {
                        bots.forEach { bot ->
                            DropdownMenuItem(
                                text = { Text(bot.name) },
                                onClick = {
                                    selectedBot = bot
                                    botExpanded = false
                                }
                            )
                        }
                    }
                }

                // Chat Selection
                Box {
                    OutlinedTextField(
                        value = selectedChat?.name ?: "Select Chat",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Chat") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = chatExpanded) }
                    )
                    Box(modifier = Modifier.matchParentSize().clickable { chatExpanded = true })
                    DropdownMenu(expanded = chatExpanded, onDismissRequest = { chatExpanded = false }) {
                        chats.forEach { chat ->
                            DropdownMenuItem(
                                text = { Text(chat.name) },
                                onClick = {
                                    selectedChat = chat
                                    chatExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = timeString,
                    onValueChange = { timeString = it },
                    label = { Text("Time (yyyy-MM-dd HH:mm)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Message Content") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val botId = selectedBot?.id
                    val chatId = selectedChat?.id
                    val time = try {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(timeString)?.time
                    } catch (e: Exception) {
                        null
                    }

                    if (botId != null && chatId != null && time != null && content.isNotBlank()) {
                        onConfirm(botId, chatId, content, time)
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
