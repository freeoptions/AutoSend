package com.autotg.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.autotg.R
import com.autotg.data.models.Chat
import com.autotg.data.models.LogStatus
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskStatus
import com.autotg.ui.components.AvatarPreviewDialog
import com.autotg.ui.components.TaskCreationDialog
import com.autotg.ui.components.isReadableAvatarUri
import com.autotg.ui.components.rememberReadableAvatarUri
import com.autotg.ui.viewmodels.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TITLE_BOARD = "AutoTG \u4efb\u52a1\u770b\u677f"
private const val DESC_INFO = "\u56fe\u6807\u8bf4\u660e"
private const val DESC_LOGS = "\u53d1\u9001\u8bb0\u5f55"
private const val DESC_SETTINGS = "\u8bbe\u7f6e"
private const val DESC_ADD_TASK = "\u6dfb\u52a0\u4efb\u52a1"
private const val TEXT_NEED_BOT = "\u8bf7\u5148\u5728\u8bbe\u7f6e\u4e2d\u6dfb\u52a0\u673a\u5668\u4eba"
private const val TEXT_EMPTY_TASKS = "\u6682\u65e0\u4efb\u52a1"
private const val TEXT_EMPTY_CHAT_TASKS = "\u8be5\u7fa4\u7ec4\u6682\u65e0\u4efb\u52a1"
private const val TEXT_UNKNOWN_CHAT = "\u672a\u77e5\u7fa4\u7ec4"
private const val TEXT_SEND_NOW_CONFIRM_TITLE = "\u7acb\u5373\u53d1\u9001\u786e\u8ba4"
private const val TEXT_SEND_NOW_CONFIRM_BODY = "\u786e\u5b9a\u8981\u7acb\u5373\u53d1\u9001\u8fd9\u6761\u6d88\u606f\u5417\uff1f\u6b64\u64cd\u4f5c\u4e0d\u4f1a\u5f71\u54cd\u5df2\u6709\u7684\u5b9a\u65f6\u8ba1\u5212\u3002"
private const val TEXT_CONFIRM_SEND = "\u786e\u8ba4\u53d1\u9001"
private const val TEXT_DELETE_CONFIRM_TITLE = "\u5220\u9664\u4efb\u52a1\u786e\u8ba4"
private const val TEXT_DELETE_CONFIRM_BODY = "\u786e\u5b9a\u8981\u5220\u9664\u8fd9\u6761\u5b9a\u65f6\u4efb\u52a1\u5417\uff1f\u6b64\u64cd\u4f5c\u4e0d\u53ef\u64a4\u9500\u3002"
private const val TEXT_CONFIRM_DELETE = "\u786e\u8ba4\u5220\u9664"
private const val TEXT_CANCEL = "\u53d6\u6d88"
private const val TEXT_ALL_CHATS = "\u6240\u6709\u7fa4\u7ec4"
private const val TEXT_SEND_NOW_DESC = "\u7acb\u5373\u53d1\u9001"
private const val TEXT_DELETE_DESC = "\u5220\u9664\u4efb\u52a1"
private const val TEXT_PENDING = "\u5f85\u53d1\u9001"
private const val TEXT_SUCCESS = "\u53d1\u9001\u6210\u529f"
private const val TEXT_FAILED = "\u53d1\u9001\u5931\u8d25"
private const val TEXT_MANUAL_LOOP = " (\u5faa\u73af)"
private const val TEXT_FINAL_FAILURE = "\u6700\u7ec8\u5931\u8d25\u539f\u56e0: "
private const val TEXT_LAST_ERROR = "\u4e0a\u6b21\u6267\u884c\u51fa\u9519: "
private const val DATE_FORMAT_TASK = "MM\u6708dd\u65e5 HH:mm"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToConfig: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val context = LocalContext.current
    val allTasks by viewModel.allTasks.collectAsState()
    val bots by viewModel.bots.collectAsState()
    val chats by viewModel.chats.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val selectedChatId by viewModel.chatFilter.collectAsState()
    val scope = rememberCoroutineScope()

    val latestLogByTaskId = remember(logs) {
        logs.groupBy { it.taskId }
            .mapValues { (_, taskLogs) -> taskLogs.maxByOrNull { it.timestamp } }
    }

    val unreadFailureCount = remember(logs) {
        logs.count { it.status == LogStatus.FAILED || it.status == LogStatus.MISSED }
    }

    val pagerState = rememberPagerState(pageCount = { bots.size.coerceAtLeast(1) })

    LaunchedEffect(bots.size) {
        if (bots.isNotEmpty() && pagerState.currentPage > bots.lastIndex) {
            pagerState.scrollToPage(bots.lastIndex)
        }
    }

    LaunchedEffect(chats, selectedChatId) {
        if (selectedChatId != null && chats.none { it.id == selectedChatId }) {
            viewModel.setChatFilter(null)
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<ScheduledTask?>(null) }
    var taskToSendImmediately by remember { mutableStateOf<ScheduledTask?>(null) }
    var taskToDelete by remember { mutableStateOf<ScheduledTask?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var previewAvatarUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(allTasks) {
        editingTask = editingTask?.takeIf { current -> allTasks.any { it.id == current.id } }
        taskToSendImmediately = taskToSendImmediately?.takeIf { current -> allTasks.any { it.id == current.id } }
        taskToDelete = taskToDelete?.takeIf { current -> allTasks.any { it.id == current.id } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(TITLE_BOARD) },
                actions = {
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = DESC_INFO)
                    }
                    Box {
                        IconButton(onClick = onNavigateToLogs) {
                            Icon(Icons.Default.List, contentDescription = DESC_LOGS)
                        }
                        if (unreadFailureCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 6.dp, end = 6.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = unreadFailureCount.coerceAtMost(99).toString(),
                                    color = MaterialTheme.colorScheme.onError,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    IconButton(onClick = onNavigateToConfig) {
                        Icon(Icons.Default.Settings, contentDescription = DESC_SETTINGS)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = DESC_ADD_TASK)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (bots.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(TEXT_NEED_BOT)
                }
            } else {
                val safeCurrentPage = pagerState.currentPage.coerceIn(0, bots.lastIndex.coerceAtLeast(0))

                ScrollableTabRow(
                    selectedTabIndex = safeCurrentPage,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    bots.forEachIndexed { index, bot ->
                        Tab(
                            selected = safeCurrentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    text = bot.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.Top,
                    key = { page -> bots.getOrNull(page)?.id ?: page.toLong() }
                ) { page ->
                    val currentBot = bots[page]
                    val readableBotAvatar by rememberReadableAvatarUri(context, currentBot.avatarPath)
                    val availableChats = remember(allTasks, chats, currentBot.id) {
                        val chatIdsForBot = allTasks
                            .asSequence()
                            .filter { it.botId == currentBot.id }
                            .map { it.chatId }
                            .distinct()
                            .toSet()
                        chats.filter { it.id in chatIdsForBot }
                    }
                    val botTasks = remember(allTasks, currentBot.id, selectedChatId) {
                        allTasks
                            .filter { it.botId == currentBot.id && (selectedChatId == null || it.chatId == selectedChatId) }
                            .sortedWith(compareBy<ScheduledTask> { it.scheduledTime }.thenBy { it.id })
                    }

                    LaunchedEffect(currentBot.id, availableChats, selectedChatId) {
                        if (availableChats.isEmpty()) {
                            if (selectedChatId != null) {
                                viewModel.setChatFilter(null)
                            }
                        } else if (selectedChatId != null && availableChats.none { it.id == selectedChatId }) {
                            viewModel.setChatFilter(availableChats.first().id)
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        ChatFilterBar(
                            chats = availableChats,
                            selectedChatId = selectedChatId,
                            botAvatar = readableBotAvatar,
                            onAvatarClick = {
                                currentBot.avatarPath
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { avatarPath ->
                                        scope.launch {
                                            if (isReadableAvatarUri(context, avatarPath)) {
                                                previewAvatarUri = avatarPath
                                            }
                                        }
                                    }
                            },
                            onChatSelected = { viewModel.setChatFilter(it) }
                        )

                        if (botTasks.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(if (selectedChatId != null) TEXT_EMPTY_CHAT_TASKS else TEXT_EMPTY_TASKS)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(botTasks, key = { it.id }) { task ->
                                    TaskItem(
                                        task = task,
                                        displayStatus = task.resolveDisplayStatus(latestLogByTaskId[task.id]?.status),
                                        chatName = chats.find { it.id == task.chatId }?.name ?: TEXT_UNKNOWN_CHAT,
                                        onClick = { editingTask = task },
                                        onToggleEnabled = { enabled -> viewModel.toggleTaskEnabled(task, enabled) },
                                        onDelete = { taskToDelete = task },
                                        onSendNow = { taskToSendImmediately = task }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            val currentBot = bots.getOrNull(
                pagerState.currentPage.coerceIn(0, bots.lastIndex.coerceAtLeast(0))
            )
            TaskCreationDialog(
                bots = bots,
                chats = chats,
                initialBotId = currentBot?.id,
                onDismiss = { showCreateDialog = false },
                onConfirm = { botId, chatId, content, parseMode, time, cron ->
                    viewModel.createTask(botId, chatId, content, parseMode, time, cron)
                    showCreateDialog = false
                }
            )
        }

        editingTask?.let { task ->
            TaskCreationDialog(
                bots = bots,
                chats = chats,
                editingTask = task,
                onDismiss = { editingTask = null },
                onConfirm = { botId, chatId, content, parseMode, time, cron ->
                    viewModel.updateTask(
                        task.copy(
                            botId = botId,
                            chatId = chatId,
                            content = content,
                            parseMode = parseMode,
                            scheduledTime = time,
                            cronExpression = cron,
                            status = TaskStatus.PENDING,
                            lastError = null
                        )
                    )
                    editingTask = null
                }
            )
        }

        taskToSendImmediately?.let { task ->
            AlertDialog(
                onDismissRequest = { taskToSendImmediately = null },
                title = { Text(TEXT_SEND_NOW_CONFIRM_TITLE) },
                text = { Text(TEXT_SEND_NOW_CONFIRM_BODY) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.sendTaskNow(task)
                            taskToSendImmediately = null
                        }
                    ) {
                        Text(TEXT_CONFIRM_SEND)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { taskToSendImmediately = null }) {
                        Text(TEXT_CANCEL)
                    }
                }
            )
        }

        taskToDelete?.let { task ->
            AlertDialog(
                onDismissRequest = { taskToDelete = null },
                title = { Text(TEXT_DELETE_CONFIRM_TITLE) },
                text = { Text(TEXT_DELETE_CONFIRM_BODY) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteTask(task)
                            taskToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(TEXT_CONFIRM_DELETE)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { taskToDelete = null }) {
                        Text(TEXT_CANCEL)
                    }
                }
            )
        }

        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text(DESC_INFO) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoRow(Icons.Default.Notifications, Color.Gray, TEXT_PENDING, "\u4efb\u52a1\u7b49\u5f85\u53d1\u9001")
                        InfoRow(Icons.Default.CheckCircle, Color(0xFF4CAF50), TEXT_SUCCESS, "\u4efb\u52a1\u53d1\u9001\u6210\u529f")
                        InfoRow(Icons.Default.Warning, MaterialTheme.colorScheme.error, TEXT_FAILED, "\u4efb\u52a1\u53d1\u9001\u5931\u8d25\u6216\u9519\u8fc7\u6267\u884c")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text(TEXT_CANCEL)
                    }
                }
            )
        }

        previewAvatarUri?.let { uri ->
            AvatarPreviewDialog(
                imageUri = uri,
                onDismiss = { previewAvatarUri = null }
            )
        }
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(desc, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatFilterBar(
    chats: List<Chat>,
    selectedChatId: Long?,
    botAvatar: String?,
    onAvatarClick: () -> Unit,
    onChatSelected: (Long?) -> Unit
) {
    val showAllChatsOption = chats.size > 1
    val effectiveSelectedChatId = selectedChatId?.takeIf { selected ->
        chats.any { it.id == selected }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = botAvatar,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (botAvatar != null) Modifier.clickable(onClick = onAvatarClick) else Modifier),
            contentScale = ContentScale.Crop,
            error = painterResource(id = R.drawable.ic_telegram)
        )

        Spacer(modifier = Modifier.width(16.dp))

        var showChatMenu by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = effectiveSelectedChatId != null,
                onClick = { showChatMenu = true },
                label = {
                    Text(
                        text = chats.find { it.id == effectiveSelectedChatId }?.name
                            ?: chats.singleOrNull()?.name
                            ?: TEXT_ALL_CHATS,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
            )
            DropdownMenu(expanded = showChatMenu, onDismissRequest = { showChatMenu = false }) {
                if (showAllChatsOption) {
                    DropdownMenuItem(
                        text = { Text(TEXT_ALL_CHATS) },
                        onClick = {
                            onChatSelected(null)
                            showChatMenu = false
                        }
                    )
                }
                chats.forEach { chat ->
                    DropdownMenuItem(
                        text = { Text(chat.name) },
                        onClick = {
                            onChatSelected(chat.id)
                            showChatMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TaskItem(
    task: ScheduledTask,
    displayStatus: TaskStatus,
    chatName: String,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSendNow: () -> Unit
) {
    val dateFormat = SimpleDateFormat(DATE_FORMAT_TASK, Locale.getDefault())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isEnabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.content,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (task.isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(status = displayStatus)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = chatName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (task.isEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${dateFormat.format(Date(task.scheduledTime))}${if (task.cronExpression != null) TEXT_MANUAL_LOOP else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSendNow, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_telegram),
                            contentDescription = TEXT_SEND_NOW_DESC,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Switch(
                        checked = task.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.scale(0.8f),
                        thumbContent = {
                            if (task.isEnabled) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        }
                    )

                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = TEXT_DELETE_DESC,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (!task.lastError.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val errorPrefix = when {
                    task.status == TaskStatus.FAILED -> TEXT_FINAL_FAILURE
                    task.retryCount > 0 -> "\u91cd\u8bd5\u4e2d\uff08\u7b2c${task.retryCount}\u6b21\u5931\u8d25\uff09: "
                    else -> TEXT_LAST_ERROR
                }
                Text(
                    text = "$errorPrefix${task.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun StatusIcon(status: TaskStatus, modifier: Modifier = Modifier) {
    when (status) {
        TaskStatus.PENDING -> Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription = TEXT_PENDING,
            tint = Color.Gray,
            modifier = modifier.size(16.dp)
        )

        TaskStatus.SUCCESS -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = TEXT_SUCCESS,
            tint = Color(0xFF4CAF50),
            modifier = modifier.size(16.dp)
        )

        TaskStatus.FAILED -> Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = TEXT_FAILED,
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(16.dp)
        )
    }
}

private fun ScheduledTask.resolveDisplayStatus(latestLogStatus: LogStatus?): TaskStatus {
    return when (latestLogStatus) {
        LogStatus.SUCCESS -> TaskStatus.SUCCESS
        LogStatus.FAILED, LogStatus.MISSED -> TaskStatus.FAILED
        LogStatus.SYSTEM -> status
        null -> status
    }
}
