package com.autotg.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskStatus
import com.autotg.ui.components.TaskCreationDialog
import com.autotg.ui.viewmodels.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToConfig: () -> Unit
) {
    val tasks by viewModel.tasks.collectAsState()
    val bots by viewModel.bots.collectAsState()
    val chats by viewModel.chats.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoTG Dashboard") },
                actions = {
                    IconButton(onClick = onNavigateToConfig) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Task")
            }
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No tasks scheduled yet.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks) { task ->
                    TaskItem(
                        task = task,
                        chatName = chats.find { it.id == task.chatId }?.name ?: "Unknown Chat",
                        onToggleEnabled = { enabled ->
                            viewModel.toggleTaskEnabled(task, enabled)
                        },
                        onDelete = {
                            viewModel.deleteTask(task)
                        }
                    )
                }
            }
        }

        if (showCreateDialog) {
            TaskCreationDialog(
                bots = bots,
                chats = chats,
                onDismiss = { showCreateDialog = false },
                onConfirm = { botId, chatId, content, time ->
                    viewModel.createTask(botId, chatId, content, time)
                    showCreateDialog = false
                }
            )
        }
    }
}

@Composable
fun TaskItem(
    task: ScheduledTask,
    chatName: String,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val isEnabled = task.isEnabled && task.status == TaskStatus.PENDING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isEnabled) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.content,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (task.isEnabled) Color.Unspecified else Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "To: $chatName • ${dateFormat.format(Date(task.scheduledTime))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.isEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                Switch(
                    checked = task.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = task.status == TaskStatus.PENDING
                )

                Spacer(modifier = Modifier.width(8.dp))
                StatusIcon(status = task.status)
            }

            if (task.status == TaskStatus.FAILED && !task.lastError.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Error: ${task.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun StatusIcon(status: TaskStatus) {
    when (status) {
        TaskStatus.PENDING -> Icon(
            Icons.Default.Schedule,
            contentDescription = "Pending",
            tint = Color.Gray
        )
        TaskStatus.SUCCESS -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Success",
            tint = Color(0xFF4CAF50)
        )
        TaskStatus.FAILED -> Icon(
            Icons.Default.Error,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error
        )
    }
}
