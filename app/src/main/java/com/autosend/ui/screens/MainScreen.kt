package com.autosend.ui.screens

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autosend.data.models.FeishuWebhook
import com.autosend.data.models.LogStatus
import com.autosend.data.models.ScheduledTask
import com.autosend.data.models.TaskLog
import com.autosend.data.models.TaskStatus
import com.autosend.data.models.isUnreadResult
import com.autosend.ui.components.PaginationControls
import com.autosend.ui.components.TaskCreationDialog
import com.autosend.ui.viewmodels.MainViewModel
import com.autosend.utils.PinyinUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TITLE_BOARD = "AutoSend 任务看板"
private const val DESC_INFO = "状态说明"
private const val DESC_LOGS = "发送记录"
private const val DESC_SETTINGS = "配置管理"
private const val DESC_ADD_TASK = "添加任务"
private const val TEXT_EMPTY_TASKS = "该目标暂无任务"
private const val TEXT_SEND_NOW_CONFIRM_TITLE = "立即发送确认"
private const val TEXT_SEND_NOW_CONFIRM_BODY = "确定要立即发送这条消息吗？此操作不会影响已有的定时计划。"
private const val TEXT_CONFIRM_SEND = "确认发送"
private const val TEXT_DELETE_CONFIRM_TITLE = "删除任务确认"
private const val TEXT_DELETE_CONFIRM_BODY = "确定要删除这条定时任务吗？此操作不可撤销。"
private const val TEXT_CONFIRM_DELETE = "确认删除"
private const val TEXT_CANCEL = "取消"
private const val TEXT_SEND_NOW_DESC = "立即发送"
private const val TEXT_DELETE_DESC = "删除任务"
private const val TEXT_PENDING = "待发送"
private const val TEXT_SUCCESS = "发送成功"
private const val TEXT_FAILED = "发送失败"
private const val TEXT_MANUAL_LOOP = "（循环）"
private const val TEXT_LUNAR_LOOP = "（阴历每年）"
private const val TEXT_FINAL_FAILURE = "最终失败原因："
private const val TEXT_LAST_ERROR = "上次执行出错："
private const val DATE_FORMAT_TASK = "MM月dd日 HH:mm"
private const val TASK_PAGE_SIZE = 10

private enum class TaskBoardFilter(val title: String) {
    ALL("全部任务"),
    THIS_MONTH("本月触发"),
    NEXT_TRIGGER("下次触发")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToConfig: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val allTasks by viewModel.allTasks.collectAsState()
    val feishuWebhooks by viewModel.feishuWebhooks.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val scope = rememberCoroutineScope()
    val latestLogByTaskId = remember(logs) {
        logs.groupBy { it.taskId }
            .mapValues { (_, taskLogs) -> taskLogs.maxByOrNull { it.timestamp } }
    }
    val unreadResultCount = remember(logs) {
        logs.count { it.isUnreadResult() }
    }
    val pagerState = rememberPagerState(pageCount = { feishuWebhooks.size.coerceAtLeast(1) })

    LaunchedEffect(feishuWebhooks.size) {
        if (feishuWebhooks.isNotEmpty() && pagerState.currentPage > feishuWebhooks.lastIndex) {
            pagerState.scrollToPage(feishuWebhooks.lastIndex)
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<ScheduledTask?>(null) }
    var taskToSendImmediately by remember { mutableStateOf<ScheduledTask?>(null) }
    var taskToDelete by remember { mutableStateOf<ScheduledTask?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var taskSearchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTaskFilterIndex by rememberSaveable { mutableIntStateOf(0) }
    val taskFilter = TaskBoardFilter.values()[
        selectedTaskFilterIndex.coerceIn(0, TaskBoardFilter.values().lastIndex)
    ]
    val webhookNames = remember(feishuWebhooks) {
        feishuWebhooks.associate { it.id to it.name }
    }
    val taskSearchIndex = remember(allTasks, webhookNames) {
        allTasks.associate { task ->
            task.id to PinyinUtils.buildSearchIndex(
                task.content,
                webhookNames[task.feishuWebhookId]
            )
        }
    }

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
                            Icon(
                                Icons.Default.List,
                                contentDescription = if (unreadResultCount > 0) {
                                    "$DESC_LOGS，有${unreadResultCount}条未读结果"
                                } else {
                                    DESC_LOGS
                                }
                            )
                        }
                        if (unreadResultCount > 0) {
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
                                    unreadResultCount.coerceAtMost(99).toString(),
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
            if (feishuWebhooks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有飞书发送目标", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "先添加 Webhook，再创建定时任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onNavigateToConfig) { Text("去配置") }
                    }
                }
            } else {
                TaskBoardControls(
                    query = taskSearchQuery,
                    onQueryChange = { taskSearchQuery = it },
                    selectedFilterIndex = selectedTaskFilterIndex,
                    onFilterChange = { selectedTaskFilterIndex = it }
                )
                val currentPage = pagerState.currentPage.coerceIn(0, feishuWebhooks.lastIndex)
                ScrollableTabRow(
                    selectedTabIndex = currentPage,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    feishuWebhooks.forEachIndexed { index, webhook ->
                        Tab(
                            selected = currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    webhook.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.Top,
                    key = { page -> feishuWebhooks.getOrNull(page)?.id ?: page }
                ) { page ->
                    FeishuTaskPage(
                        webhook = feishuWebhooks[page],
                        allTasks = allTasks,
                        latestLogByTaskId = latestLogByTaskId,
                        searchQuery = taskSearchQuery,
                        taskFilter = taskFilter,
                        taskSearchIndex = taskSearchIndex,
                        onEdit = { editingTask = it },
                        onToggleEnabled = viewModel::toggleTaskEnabled,
                        onDelete = { taskToDelete = it },
                        onSendNow = { taskToSendImmediately = it }
                    )
                }
            }
        }

        if (showCreateDialog) {
            TaskCreationDialog(
                feishuWebhooks = feishuWebhooks,
                initialFeishuWebhookId = feishuWebhooks.getOrNull(pagerState.currentPage)?.id,
                onDismiss = { showCreateDialog = false },
                onConfirm = { webhookId, content, time, cron, lunarMonth, lunarDay, lunarLeapMonth ->
                    viewModel.createTask(
                        webhookId,
                        content,
                        time,
                        cron,
                        lunarMonth,
                        lunarDay,
                        lunarLeapMonth
                    )
                    showCreateDialog = false
                }
            )
        }

        editingTask?.let { task ->
            TaskCreationDialog(
                feishuWebhooks = feishuWebhooks,
                editingTask = task,
                onDismiss = { editingTask = null },
                onConfirm = { webhookId, content, time, cron, lunarMonth, lunarDay, lunarLeapMonth ->
                    viewModel.updateTask(
                        task.copy(
                            deliveryChannel = com.autosend.data.models.DeliveryChannel.FEISHU,
                            botId = null,
                            chatId = null,
                            feishuWebhookId = webhookId,
                            content = content,
                            parseMode = com.autosend.data.models.MessageParseMode.NONE,
                            scheduledTime = time,
                            cronExpression = cron,
                            lunarMonth = lunarMonth,
                            lunarDay = lunarDay,
                            lunarLeapMonth = lunarLeapMonth,
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
                    TextButton(onClick = {
                        viewModel.sendTaskNow(task)
                        taskToSendImmediately = null
                    }) { Text(TEXT_CONFIRM_SEND) }
                },
                dismissButton = {
                    TextButton(onClick = { taskToSendImmediately = null }) { Text(TEXT_CANCEL) }
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(TEXT_CONFIRM_DELETE) }
                },
                dismissButton = {
                    TextButton(onClick = { taskToDelete = null }) { Text(TEXT_CANCEL) }
                }
            )
        }

        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text(DESC_INFO) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoRow(Icons.Default.Notifications, Color.Gray, TEXT_PENDING, "任务等待发送")
                        InfoRow(Icons.Default.CheckCircle, Color(0xFF4CAF50), TEXT_SUCCESS, "任务发送成功")
                        InfoRow(Icons.Default.Warning, MaterialTheme.colorScheme.error, TEXT_FAILED, "任务发送失败或错过执行")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) { Text(TEXT_CANCEL) }
                }
            )
        }
    }
}

@Composable
private fun TaskBoardControls(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedFilterIndex: Int,
    onFilterChange: (Int) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text("搜索任务、目标或拼音") },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = "搜索")
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "清除搜索")
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.large
    )
    ScrollableTabRow(
        selectedTabIndex = selectedFilterIndex,
        edgePadding = 16.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {}
    ) {
        TaskBoardFilter.values().forEachIndexed { index, filter ->
            Tab(
                selected = selectedFilterIndex == index,
                onClick = { onFilterChange(index) },
                text = { Text(filter.title) }
            )
        }
    }
}

@Composable
private fun FeishuTaskPage(
    webhook: FeishuWebhook,
    allTasks: List<ScheduledTask>,
    latestLogByTaskId: Map<Long, TaskLog?>,
    searchQuery: String,
    taskFilter: TaskBoardFilter,
    taskSearchIndex: Map<Long, String>,
    onEdit: (ScheduledTask) -> Unit,
    onToggleEnabled: (ScheduledTask, Boolean) -> Unit,
    onDelete: (ScheduledTask) -> Unit,
    onSendNow: (ScheduledTask) -> Unit
) {
    val tasks = remember(allTasks, webhook.id) {
        allTasks
            .filter { it.feishuWebhookId == webhook.id }
            .sortedWith(compareBy<ScheduledTask> { it.scheduledTime }.thenBy { it.id })
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("飞书通知", style = MaterialTheme.typography.labelMedium)
                Text(webhook.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        TaskList(
            modifier = Modifier.weight(1f),
            tasks = tasks,
            latestLogByTaskId = latestLogByTaskId,
            searchQuery = searchQuery,
            taskFilter = taskFilter,
            taskSearchIndex = taskSearchIndex,
            targetName = { webhook.name },
            onEdit = onEdit,
            onToggleEnabled = onToggleEnabled,
            onDelete = onDelete,
            onSendNow = onSendNow
        )
    }
}

@Composable
private fun TaskList(
    tasks: List<ScheduledTask>,
    latestLogByTaskId: Map<Long, TaskLog?>,
    searchQuery: String,
    taskFilter: TaskBoardFilter,
    taskSearchIndex: Map<Long, String>,
    targetName: (ScheduledTask) -> String,
    onEdit: (ScheduledTask) -> Unit,
    onToggleEnabled: (ScheduledTask, Boolean) -> Unit,
    onDelete: (ScheduledTask) -> Unit,
    onSendNow: (ScheduledTask) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredTasks = remember(tasks, searchQuery, taskFilter, taskSearchIndex) {
        val searchMatchedTasks = tasks.filter { task ->
            PinyinUtils.matches(taskSearchIndex[task.id].orEmpty(), searchQuery)
        }
        applyTaskFilter(searchMatchedTasks, taskFilter)
    }
    val pageCount = (filteredTasks.size + TASK_PAGE_SIZE - 1) / TASK_PAGE_SIZE
    var currentPage by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val displayPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    LaunchedEffect(searchQuery, taskFilter) {
        currentPage = 0
        listState.scrollToItem(0)
    }
    LaunchedEffect(currentPage) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(pageCount) {
        currentPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    Column(modifier.fillMaxWidth()) {
        if (filteredTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(if (tasks.isEmpty()) TEXT_EMPTY_TASKS else "没有找到匹配的任务")
            }
        } else {
            val pageTasks = filteredTasks
                .drop(displayPage * TASK_PAGE_SIZE)
                .take(TASK_PAGE_SIZE)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pageTasks, key = { it.id }) { task ->
                    TaskItem(
                        task = task,
                        displayStatus = task.resolveDisplayStatus(latestLogByTaskId[task.id]?.status),
                        targetName = targetName(task),
                        onClick = { onEdit(task) },
                        onToggleEnabled = { onToggleEnabled(task, it) },
                        onDelete = { onDelete(task) },
                        onSendNow = { onSendNow(task) }
                    )
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

private fun applyTaskFilter(tasks: List<ScheduledTask>, filter: TaskBoardFilter): List<ScheduledTask> {
    if (filter == TaskBoardFilter.ALL) return tasks

    val now = System.currentTimeMillis()
    return when (filter) {
        TaskBoardFilter.ALL -> tasks
        TaskBoardFilter.THIS_MONTH -> {
            val calendar = Calendar.getInstance().apply { timeInMillis = now }
            val monthStart = calendar.clone() as Calendar
            monthStart.set(Calendar.DAY_OF_MONTH, 1)
            monthStart.set(Calendar.HOUR_OF_DAY, 0)
            monthStart.set(Calendar.MINUTE, 0)
            monthStart.set(Calendar.SECOND, 0)
            monthStart.set(Calendar.MILLISECOND, 0)
            val nextMonthStart = (monthStart.clone() as Calendar).apply {
                add(Calendar.MONTH, 1)
            }.timeInMillis
            tasks.filter {
                it.isEnabled &&
                    it.scheduledTime > now &&
                    it.scheduledTime in monthStart.timeInMillis until nextMonthStart
            }
        }
        TaskBoardFilter.NEXT_TRIGGER -> {
            val candidates = tasks.filter { it.isEnabled && it.scheduledTime > 0L }
            val nextTime = candidates
                .filter { it.scheduledTime > now }
                .minOfOrNull { it.scheduledTime }
                ?: candidates.minOfOrNull { it.scheduledTime }
            if (nextTime == null) emptyList() else candidates.filter { it.scheduledTime == nextTime }
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    title: String,
    desc: String
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(desc, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun TaskItem(
    task: ScheduledTask,
    displayStatus: TaskStatus,
    targetName: String,
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
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.content,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (task.isEnabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            Color.Gray
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(displayStatus)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            targetName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (task.isEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${dateFormat.format(Date(task.scheduledTime))}${when {
                                task.isLunarRecurring -> TEXT_LUNAR_LOOP
                                !task.cronExpression.isNullOrBlank() -> TEXT_MANUAL_LOOP
                                else -> ""
                            }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSendNow, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = TEXT_SEND_NOW_DESC,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Switch(
                        checked = task.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.scale(0.8f),
                        thumbContent = {
                            Icon(
                                imageVector = if (task.isEnabled) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    )
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = TEXT_DELETE_DESC,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (!task.lastError.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                val errorPrefix = when {
                    task.status == TaskStatus.FAILED -> TEXT_FINAL_FAILURE
                    task.retryCount > 0 -> "重试中（第${task.retryCount}次失败）："
                    else -> TEXT_LAST_ERROR
                }
                Text(
                    "$errorPrefix${task.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(status: TaskStatus, modifier: Modifier = Modifier) {
    when (status) {
        TaskStatus.PENDING -> Icon(
            Icons.Default.Notifications,
            contentDescription = TEXT_PENDING,
            tint = Color.Gray,
            modifier = modifier.size(16.dp)
        )
        TaskStatus.SUCCESS -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = TEXT_SUCCESS,
            tint = Color(0xFF4CAF50),
            modifier = modifier.size(16.dp)
        )
        TaskStatus.FAILED -> Icon(
            Icons.Default.Warning,
            contentDescription = TEXT_FAILED,
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(16.dp)
        )
    }
}

private fun ScheduledTask.resolveDisplayStatus(latestLogStatus: LogStatus?): TaskStatus =
    when (latestLogStatus) {
        LogStatus.SUCCESS -> TaskStatus.SUCCESS
        LogStatus.FAILED, LogStatus.MISSED -> TaskStatus.FAILED
        LogStatus.SYSTEM, null -> status
    }
