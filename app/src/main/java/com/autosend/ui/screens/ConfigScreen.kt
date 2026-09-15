package com.autosend.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autosend.data.models.FeishuWebhook
import com.autosend.service.TaskExecutionService
import com.autosend.ui.viewmodels.ConfigViewModel
import com.autosend.ui.theme.AutoSendColors
import com.autosend.utils.PermissionUtils
import com.autosend.utils.RecoveryScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val exportUri by viewModel.exportUri.collectAsState()
    val webhooks by viewModel.feishuWebhooks.collectAsState()
    val tabs = listOf("飞书", "设置")
    val pager = rememberPagerState(pageCount = { tabs.size })
    var editingWebhook by remember { mutableStateOf<FeishuWebhook?>(null) }
    var showAddWebhook by remember { mutableStateOf(false) }
    var deletingWebhook by remember { mutableStateOf<FeishuWebhook?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf("") }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(it, flags) }
            viewModel.saveExportUri(it.toString())
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                    reader.readText()
                } ?: error("无法读取文件")
            }.onSuccess { json ->
                viewModel.importData(json) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        showImport = false
                        importMessage = ""
                    } else {
                        importMessage = message
                    }
                }
            }.onFailure {
                importMessage = "读取文件失败：${it.message}"
            }
        }
    }

    Scaffold(
        containerColor = AutoSendColors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "配置管理",
                        style = MaterialTheme.typography.titleLarge,
                        color = AutoSendColors.ink
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = AutoSendColors.blue
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.exportData { json ->
                            if (exportUri != null) {
                                viewModel.performFileExport(json) { _, message ->
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            } else {
                                clipboard.setText(AnnotatedString(json))
                                Toast.makeText(
                                    context,
                                    "配置已复制到剪贴板",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }) { Text("导出") }
                    TextButton(onClick = { showImport = true }) { Text("导入") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = AutoSendColors.background
                )
            )
        },
        floatingActionButton = {
            if (pager.currentPage == 0) {
                FloatingActionButton(
                    onClick = { showAddWebhook = true },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = AutoSendColors.blue,
                    contentColor = Color.White
                ) {
                    Text("＋", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = pager.currentPage,
                containerColor = Color.Transparent,
                contentColor = AutoSendColors.blue,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> FeishuWebhookList(
                        webhooks = webhooks,
                        onEdit = { editingWebhook = it },
                        onDelete = { deletingWebhook = it },
                        onTest = { webhook ->
                            viewModel.testFeishuWebhook(webhook) { _, message ->
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> SettingsTab(
                        exportUri = exportUri,
                        onSelectDirectory = { exportPicker.launch(null) },
                        modifier = Modifier.fillMaxSize(),
                        context = context
                    )
                }
            }
        }
    }

    deletingWebhook?.let { webhook ->
        ConfirmDeleteDialog(
            title = "删除飞书 Webhook",
            message = "确定删除“${webhook.name}”吗？关联的定时任务也将无法继续发送。",
            onConfirm = {
                viewModel.deleteFeishuWebhook(webhook)
                deletingWebhook = null
            },
            onDismiss = { deletingWebhook = null }
        )
    }

    if (showAddWebhook || editingWebhook != null) {
        AddFeishuWebhookDialog(
            initial = editingWebhook,
            onDismiss = {
                showAddWebhook = false
                editingWebhook = null
            }
        ) { name, url, secret ->
            if (editingWebhook == null) {
                viewModel.addFeishuWebhook(name, url, secret) { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    showAddWebhook = false
                }
            } else {
                viewModel.updateFeishuWebhook(
                    editingWebhook!!.copy(name = name, webhookUrl = url, secret = secret)
                ) { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    editingWebhook = null
                }
            }
        }
    }

    if (showImport) {
        ImportDialog(
            message = importMessage,
            onPickFile = { importPicker.launch(arrayOf("application/json")) },
            onImport = { json ->
                viewModel.importData(json) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        showImport = false
                        importMessage = ""
                    } else {
                        importMessage = message
                    }
                }
            },
            onDismiss = {
                showImport = false
                importMessage = ""
            }
        )
    }
}

@Composable
private fun FeishuWebhookList(
    webhooks: List<FeishuWebhook>,
    onEdit: (FeishuWebhook) -> Unit,
    onDelete: (FeishuWebhook) -> Unit,
    onTest: (FeishuWebhook) -> Unit,
    modifier: Modifier = Modifier
) {
    if (webhooks.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = AutoSendColors.blue
                )
                Spacer(Modifier.height(12.dp))
                Text("还没有飞书 Webhook", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点击右下角加号添加发送目标",
                    style = MaterialTheme.typography.bodySmall,
                    color = AutoSendColors.muted
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = 20.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(webhooks, key = { it.id }) { webhook ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(webhook) },
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, AutoSendColors.line),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = "飞书 Webhook",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(AutoSendColors.blueTint)
                            .padding(9.dp),
                        tint = AutoSendColors.blue
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            webhook.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            webhook.webhookUrl,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = AutoSendColors.muted
                        )
                    }
                    TextButton(onClick = { onTest(webhook) }) { Text("测试") }
                    IconButton(onClick = { onDelete(webhook) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = AutoSendColors.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text("确定删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddFeishuWebhookDialog(
    initial: FeishuWebhook?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String?) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.webhookUrl.orEmpty()) }
    var secret by remember { mutableStateOf(initial?.secret.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加飞书 Webhook" else "编辑飞书 Webhook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("目标名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Webhook 地址") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("签名密钥（可选）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name.trim(), url.trim(), secret.trim().takeIf { it.isNotEmpty() })
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) { Text(if (initial == null) "添加" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ImportDialog(
    message: String,
    onPickFile: () -> Unit,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var json by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    placeholder = { Text("粘贴导出的 JSON") }
                )
                if (message.isNotBlank()) {
                    Text(message, color = AutoSendColors.error)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onPickFile) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("选择文件")
                }
                Button(onClick = { onImport(json) }, enabled = json.isNotBlank()) {
                    Text("导入")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SettingsTab(
    exportUri: String?,
    onSelectDirectory: () -> Unit,
    modifier: Modifier = Modifier,
    context: Context
) {
    val readableExportDirectory = remember(exportUri) {
        formatExportDirectory(context, exportUri)
    }

    Column(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PermissionGuideCard(context)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, AutoSendColors.line),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("导出设置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    readableExportDirectory ?: "未设置（点击下方按钮选择目录）",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = AutoSendColors.muted
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSelectDirectory) {
                    Text(if (exportUri == null) "选择输出目录" else "更改输出目录")
                }
            }
        }
    }
}

private fun formatExportDirectory(context: Context, uriString: String?): String? {
    if (uriString.isNullOrBlank()) return null

    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return Uri.decode(uriString)
    val documentId = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrNull()?.let(Uri::decode)

    if (!documentId.isNullOrBlank()) {
        val storageId = documentId.substringBefore(':', missingDelimiterValue = "")
        val relativePath = documentId.substringAfter(':', missingDelimiterValue = documentId)
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.split('/')
            ?.joinToString(" / ")

        val storageName = when (storageId.lowercase(Locale.ROOT)) {
            "primary" -> "内部存储"
            "home" -> "主目录"
            "raw" -> "内部存储"
            else -> storageId.takeIf { it.isNotBlank() } ?: "存储"
        }

        if (!relativePath.isNullOrBlank()) {
            return "$storageName / $relativePath"
        }
        if (storageId.isNotBlank()) return storageName
    }

    DocumentFile.fromTreeUri(context, uri)?.name?.takeIf { it.isNotBlank() }?.let {
        return it
    }

    return Uri.decode(uriString)
}

@Composable
private fun PermissionGuideCard(context: Context) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }
    val schedule = remember(tick) { RecoveryScheduler.scheduleSnapshot(context) }
    val alarm = remember(tick) { PermissionUtils.canScheduleExactAlarms(context) }
    val battery = remember(tick) { PermissionUtils.isBatteryOptimizationIgnored(context) }
    val notifications = remember(tick) {
        PermissionUtils.isNotificationChannelEnabled(
            context,
            TaskExecutionService.NOTIFICATION_CHANNEL_ID
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, AutoSendColors.line),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("后台可靠性", style = MaterialTheme.typography.titleMedium)
            Text("精确闹钟：${if (alarm) "允许" else "未授权"}")
            Text("每日恢复检查：${formatDiagnosticTime(schedule.nextDailyRecoveryAt)}")
            ReliabilityItem("忽略电池优化", if (battery) "已允许" else "需要处理", battery) {
                PermissionUtils.requestIgnoreBatteryOptimizations(context)
            }
            ReliabilityItem("任务执行通知", if (notifications) "已启用" else "需要处理", notifications) {
                PermissionUtils.openNotificationChannelSettings(
                    context,
                    TaskExecutionService.NOTIFICATION_CHANNEL_ID
                )
            }
            Button(onClick = { PermissionUtils.openExactAlarmSettings(context) }) {
                Text("检查精确闹钟权限")
            }
        }
    }
}

@Composable
private fun ReliabilityItem(
    title: String,
    desc: String,
    status: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (status) "正常" else "处理",
            color = if (status) AutoSendColors.success else AutoSendColors.warning
        )
    }
}

private fun formatDiagnosticTime(timeMillis: Long): String =
    if (timeMillis <= 0L) {
        "暂无记录"
    } else {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
    }
