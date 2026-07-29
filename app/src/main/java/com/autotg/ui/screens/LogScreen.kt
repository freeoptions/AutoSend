package com.autotg.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autotg.data.models.LogStatus
import com.autotg.data.models.TaskLog
import com.autotg.ui.viewmodels.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TITLE_LOGS = "\u53d1\u9001\u8bb0\u5f55"
private const val TITLE_BACK = "\u8fd4\u56de"
private const val TITLE_CLEAR = "\u6e05\u7a7a\u65e5\u5fd7"
private const val TITLE_CLEAR_CONFIRM = "\u6e05\u7a7a\u8bb0\u5f55\u786e\u8ba4"
private const val TEXT_CLEAR_CONFIRM = "\u786e\u5b9a\u8981\u6e05\u7a7a\u6240\u6709\u53d1\u9001\u8bb0\u5f55\u5417\uff1f\u6b64\u64cd\u4f5c\u4e0d\u53ef\u64a4\u9500\u3002"
private const val ACTION_CONFIRM_CLEAR = "\u786e\u8ba4\u6e05\u7a7a"
private const val ACTION_CANCEL = "\u53d6\u6d88"
private const val EMPTY_LOGS = "\u6682\u65e0\u53d1\u9001\u8bb0\u5f55"
private const val STATUS_SUCCESS = "\u53d1\u9001\u6210\u529f"
private const val STATUS_FAILED = "\u53d1\u9001\u5931\u8d25"
private const val STATUS_MISSED = "\u9519\u8fc7\u6267\u884c"
private const val STATUS_SYSTEM = "\u7cfb\u7edf\u8bca\u65ad"
private const val LABEL_BOT_CHAT = "\u673a\u5668\u4eba: %s  \u7fa4\u7ec4: %s"
private const val ACTION_RETRY = "\u70b9\u51fb\u91cd\u8bd5"
private const val LABEL_DETAILS = "\u8be6\u60c5: %s"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(TITLE_LOGS) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = TITLE_BACK)
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = TITLE_CLEAR)
                    }
                }
            )
        }
    ) { padding ->
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(TITLE_CLEAR_CONFIRM) },
                text = { Text(TEXT_CLEAR_CONFIRM) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.clearLogs()
                            showClearConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(ACTION_CONFIRM_CLEAR)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text(ACTION_CANCEL)
                    }
                }
            )
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(EMPTY_LOGS)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { log ->
                    LogItem(log, onRetry = { viewModel.retryFailedLog(log) })
                }
            }
        }
    }
}

@Composable
fun LogItem(log: TaskLog, onRetry: () -> Unit) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (log.status) {
                        LogStatus.SUCCESS -> STATUS_SUCCESS
                        LogStatus.FAILED -> STATUS_FAILED
                        LogStatus.MISSED -> STATUS_MISSED
                        LogStatus.SYSTEM -> STATUS_SYSTEM
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = when (log.status) {
                        LogStatus.SUCCESS -> Color(0xFF4CAF50)
                        LogStatus.FAILED -> MaterialTheme.colorScheme.error
                        LogStatus.MISSED -> Color(0xFFFF9800)
                        LogStatus.SYSTEM -> Color(0xFF2196F3)
                    }
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = log.taskContent,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = LABEL_BOT_CHAT.format(log.botName, log.chatName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (log.status == LogStatus.FAILED || log.status == LogStatus.MISSED) {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(ACTION_RETRY, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (!log.errorMessage.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val messageColor = when (log.status) {
                    LogStatus.SUCCESS -> Color(0xFF4CAF50)
                    LogStatus.FAILED -> MaterialTheme.colorScheme.error
                    LogStatus.MISSED -> Color(0xFFFF9800)
                    LogStatus.SYSTEM -> Color(0xFF2196F3)
                }
                Surface(
                    color = messageColor.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (log.status == LogStatus.SUCCESS) {
                            log.errorMessage!!
                        } else {
                            LABEL_DETAILS.format(log.errorMessage)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = messageColor,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}
