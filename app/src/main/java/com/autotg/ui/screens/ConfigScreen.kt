package com.autotg.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.ui.viewmodels.ConfigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel = viewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Bots", "Chats")

    var showAddBotDialog by remember { mutableStateOf(false) }
    var showAddChatDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (selectedTab == 0) showAddBotDialog = true else showAddChatDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> BotList(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
                1 -> ChatList(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showAddBotDialog) {
        AddBotDialog(
            onDismiss = { showAddBotDialog = false },
            onConfirm = { name, token ->
                viewModel.addBot(name, token)
                showAddBotDialog = false
            }
        )
    }

    if (showAddChatDialog) {
        AddChatDialog(
            onDismiss = { showAddChatDialog = false },
            onConfirm = { name, chatId ->
                viewModel.addChat(name, chatId)
                showAddChatDialog = false
            }
        )
    }
}

@Composable
fun BotList(
    viewModel: ConfigViewModel,
    modifier: Modifier = Modifier
) {
    val bots by viewModel.bots.collectAsState()

    LazyColumn(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(bots) { bot ->
            BotCard(bot = bot, onDelete = { viewModel.deleteBot(bot) })
        }
    }
}

@Composable
fun BotCard(bot: Bot, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = bot.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Token: ${bot.token.take(10)}...",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
fun ChatList(
    viewModel: ConfigViewModel,
    modifier: Modifier = Modifier
) {
    val chats by viewModel.chats.collectAsState()

    LazyColumn(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(chats) { chat ->
            ChatCard(chat = chat, onDelete = { viewModel.deleteChat(chat) })
        }
    }
}

@Composable
fun ChatCard(chat: Chat, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = chat.name, style = MaterialTheme.typography.titleMedium)
                Text(text = "ID: ${chat.chatId}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
fun AddBotDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Bot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, token) },
                enabled = name.isNotBlank() && token.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddChatDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Chat ID") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, chatId) },
                enabled = name.isNotBlank() && chatId.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
