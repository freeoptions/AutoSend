package com.autosend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autosend.data.models.LogStatus
import com.autosend.data.models.TaskLog
import com.autosend.ui.components.PaginationControls
import com.autosend.ui.theme.AutoSendColors
import com.autosend.ui.viewmodels.MainViewModel
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
private const val LABEL_BOT_CHAT = "通道: %s  目标: %s"
private const val ACTION_RETRY = "\u70b9\u51fb\u91cd\u8bd5"
private const val LABEL_FAILURE_REASON = "失败原因：%s"
private const val LOG_PAGE_SIZE = 10

private enum class LogFilter(val title: String) {
    ALL("全部"),
    SUCCESS("发送成功"),
    FAILED("发送失败")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    val visibleLogs = logs.filter { it.status != LogStatus.SYSTEM }
    var selectedFilterIndex by rememberSaveable { mutableIntStateOf(0) }
    val logFilter = LogFilter.values()[
        selectedFilterIndex.coerceIn(0, LogFilter.values().lastIndex)
    ]
    val filteredLogs = remember(visibleLogs, logFilter) {
        when (logFilter) {
            LogFilter.ALL -> visibleLogs
            LogFilter.SUCCESS -> visibleLogs.filter { it.status == LogStatus.SUCCESS }
            LogFilter.FAILED -> visibleLogs.filter {
                it.status == LogStatus.FAILED || it.status == LogStatus.MISSED
            }
        }
    }
    val pageCount = (filteredLogs.size + LOG_PAGE_SIZE - 1) / LOG_PAGE_SIZE
    var currentPage by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val displayPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(logs) {
        if (visibleLogs.any { !it.isRead }) {
            viewModel.markLogsAsRead()
        }
    }
    LaunchedEffect(logFilter) {
        currentPage = 0
        listState.scrollToItem(0)
    }
    LaunchedEffect(currentPage) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(pageCount) {
        currentPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    Scaffold(
        containerColor = AutoSendColors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        TITLE_LOGS,
                        style = MaterialTheme.typography.titleLarge,
                        color = AutoSendColors.ink
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = TITLE_BACK, tint = AutoSendColors.blue)
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = TITLE_CLEAR, tint = AutoSendColors.error)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = AutoSendColors.background
                )
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedFilterIndex,
                edgePadding = 20.dp,
                containerColor = Color.Transparent,
                contentColor = AutoSendColors.blue,
                divider = {}
            ) {
                LogFilter.values().forEachIndexed { index, filter ->
                    Tab(
                        selected = selectedFilterIndex == index,
                        onClick = { selectedFilterIndex = index },
                        text = { Text(filter.title) }
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (visibleLogs.isEmpty()) EMPTY_LOGS else "没有找到匹配的记录")
                }
            } else {
                val pageLogs = filteredLogs
                    .drop(displayPage * LOG_PAGE_SIZE)
                    .take(LOG_PAGE_SIZE)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(pageLogs, key = { it.id }) { log ->
                        LogItem(log, onRetry = { viewModel.retryFailedLog(log) })
                    }
                }
            }
            PaginationControls(
                currentPage = displayPage,
                pageCount = pageCount,
                onPageChange = { currentPage = it }
            )
        }
    }
}

@Composable
fun LogItem(log: TaskLog, onRetry: () -> Unit) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val userFacingDetails = log.userFacingDetails()
    val statusTitle = when (log.status) {
        LogStatus.SUCCESS -> STATUS_SUCCESS
        LogStatus.FAILED, LogStatus.MISSED -> STATUS_FAILED
        LogStatus.SYSTEM -> ""
    }
    val statusColor = when (log.status) {
        LogStatus.SUCCESS -> AutoSendColors.success
        LogStatus.FAILED, LogStatus.MISSED -> AutoSendColors.error
        LogStatus.SYSTEM -> Color.Transparent
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, AutoSendColors.line),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor
                )
                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = AutoSendColors.muted.copy(alpha = 0.75f)
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
                    color = AutoSendColors.muted,
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

            if (!userFacingDetails.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = statusColor.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = LABEL_FAILURE_REASON.format(userFacingDetails),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

private fun TaskLog.userFacingDetails(): String? {
    return when (status) {
        LogStatus.SUCCESS, LogStatus.SYSTEM -> null
        LogStatus.MISSED -> "任务未按计划时间发送，已进入补发流程。"
        LogStatus.FAILED -> errorMessage?.let { message ->
            message.substringAfter("原因:", missingDelimiterValue = message)
                .trim()
                .takeIf { it.isNotBlank() }
        }
    }
}
