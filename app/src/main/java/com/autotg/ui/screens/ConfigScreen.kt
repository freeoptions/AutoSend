package com.autotg.ui.screens

import androidx.compose.foundation.layout.aspectRatio
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.ui.components.AvatarPreviewDialog
import com.autotg.ui.components.isReadableAvatarUri
import com.autotg.ui.components.isUriInTree
import com.autotg.ui.components.rememberReadableAvatarUri
import com.autotg.ui.viewmodels.ConfigViewModel
import android.widget.Toast
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.net.Uri
import androidx.compose.foundation.background

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch

import androidx.compose.ui.graphics.Color
import com.autotg.service.KeepAliveDiagnostics
import com.autotg.service.KeepAliveService
import com.autotg.utils.PermissionUtils
import com.autotg.utils.RecoveryScheduler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.autotg.data.models.AvatarMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val AVATAR_ASSET_BATCH_SIZE = 36

private data class AvatarAssetLoadState(
    val images: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val exportUri by viewModel.exportUri.collectAsState()
    val avatarDirUri by viewModel.avatarDirUri.collectAsState()
    val avatarMoveTargetUri by viewModel.avatarMoveTargetUri.collectAsState()
    val readableExportUri = remember(exportUri) {
        exportUri?.let { android.net.Uri.decode(it) } ?: "未设置 (点击下方按钮选择目录)"
    }
    val readableAvatarDirUri = remember(avatarDirUri) {
        avatarDirUri?.let { android.net.Uri.decode(it) } ?: "未设置 (设置后可快速选择图片)"
    }
    val readableAvatarMoveTargetUri = remember(avatarMoveTargetUri) {
        avatarMoveTargetUri?.let { android.net.Uri.decode(it) } ?: "未设置 (用于移动已标记头像)"
    }

    val tabs = listOf("机器人", "群组", "设置")
    LaunchedEffect(avatarMoveTargetUri) {
        viewModel.cleanupStaleAvatarMarks()
    }

    val pagerState = rememberPagerState(pageCount = { tabs.size })

    var showAddBotDialog by remember { mutableStateOf(false) }
    var showAddChatDialog by remember { mutableStateOf(false) }
    var editingBot by remember { mutableStateOf<Bot?>(null) }
    var editingChat by remember { mutableStateOf<Chat?>(null) }
    var botToDelete by remember { mutableStateOf<Bot?>(null) }
    var chatToDelete by remember { mutableStateOf<Chat?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showMarkedAvatarsDialog by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf("") }

    val openDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            viewModel.saveExportUri(it.toString())
        }
    }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    val json = inputStream.bufferedReader().use { reader -> reader.readText() }
                    viewModel.importData(json) { success, msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        if (success) {
                            showImportDialog = false
                            importStatusMessage = ""
                        } else {
                            importStatusMessage = msg
                        }
                    }
                }
            } catch (e: Exception) {
                importStatusMessage = "读取文件失败: ${e.message}"
            }
        }
    }

    var onImagePicked by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            onImagePicked?.invoke(it.toString())
        }
    }

    val openAvatarDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            viewModel.saveAvatarDirUri(it.toString())
        }
    }

    val openAvatarMoveTargetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            viewModel.saveAvatarMoveTargetUri(it.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.exportData { json ->
                            if (exportUri != null) {
                                viewModel.performFileExport(json) { success, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    if (!success) {
                                        // If file export fails, fallback to clipboard
                                        clipboardManager.setText(AnnotatedString(json))
                                    }
                                }
                            } else {
                                clipboardManager.setText(AnnotatedString(json))
                                val intent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, json)
                                    type = "text/plain"
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        intent,
                                        "备份数据"
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    "已复制到剪贴板，请设置导出目录以直接导出文件",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }) {
                        Text("导出", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { showImportDialog = true }) {
                        Text("导入", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        floatingActionButton = {
            if (pagerState.currentPage < 2) {
                FloatingActionButton(onClick = {
                    if (pagerState.currentPage == 0) showAddBotDialog =
                        true else showAddChatDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = { Text(title) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> BotList(
                        viewModel = viewModel,
                        avatarMoveTargetUri = avatarMoveTargetUri,
                        onEdit = { editingBot = it },
                        onDelete = { botToDelete = it },
                        modifier = Modifier.fillMaxSize()
                    )

                    1 -> ChatList(
                        viewModel = viewModel,
                        avatarMoveTargetUri = avatarMoveTargetUri,
                        onEdit = { editingChat = it },
                        onDelete = { chatToDelete = it },
                        modifier = Modifier.fillMaxSize()
                    )

                    2 -> SettingsTab(
                        exportUri = readableExportUri,
                        onSelectDirectory = { openDirectoryLauncher.launch(null) },
                        avatarDirUri = readableAvatarDirUri,
                        onSelectAvatarDirectory = { openAvatarDirectoryLauncher.launch(null) },
                        avatarMoveTargetUri = readableAvatarMoveTargetUri,
                        onSelectAvatarMoveTarget = { openAvatarMoveTargetLauncher.launch(null) },
                        onShowMarkedAvatars = { showMarkedAvatarsDialog = true },
                        modifier = Modifier.fillMaxSize(),
                        context = context
                    )
                }
            }
        }
    }

    if (botToDelete != null) {
        AlertDialog(
            onDismissRequest = { botToDelete = null },
            title = { Text("删除机器人确认") },
            text = { Text("确定要删除机器人“${botToDelete?.name}”吗？这将同时删除所有关联的定时任务。") },
            confirmButton = {
                Button(
                    onClick = {
                        botToDelete?.let { viewModel.deleteBot(it) }
                        botToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确定删除") }
            },
            dismissButton = {
                TextButton(onClick = { botToDelete = null }) { Text("取消") }
            }
        )
    }

    if (chatToDelete != null) {
        AlertDialog(
            onDismissRequest = { chatToDelete = null },
            title = { Text("删除群组确认") },
            text = { Text("确定要删除群组“${chatToDelete?.name}”吗？这将同时删除所有关联的定时任务。") },
            confirmButton = {
                Button(
                    onClick = {
                        chatToDelete?.let { viewModel.deleteChat(it) }
                        chatToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确定删除") }
            },
            dismissButton = {
                TextButton(onClick = { chatToDelete = null }) { Text("取消") }
            }
        )
    }

    if (editingBot != null) {
        AddBotDialog(
            initialBot = editingBot,
            avatarDirUri = avatarDirUri,
            context = context,
            onDismiss = { editingBot = null },
            onPickImage = { callback ->
                onImagePicked = callback
                imagePickerLauncher.launch("image/*")
            },
            onConfirm = { name, token, avatar ->
                editingBot?.let {
                    viewModel.updateBot(
                        it.copy(name = name, token = token, avatarPath = avatar)
                    ) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        if (success) {
                            editingBot = null
                        }
                    }
                }
            }
        )
    }

    if (editingChat != null) {
        AddChatDialog(
            initialChat = editingChat,
            avatarDirUri = avatarDirUri,
            context = context,
            onDismiss = { editingChat = null },
            onPickImage = { callback ->
                onImagePicked = callback
                imagePickerLauncher.launch("image/*")
            },
            onConfirm = { name, chatId, avatar ->
                editingChat?.let {
                    viewModel.updateChat(
                        it.copy(name = name, chatId = chatId, avatarPath = avatar)
                    ) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        if (success) {
                            editingChat = null
                        }
                    }
                }
            }
        )
    }

    if (showImportDialog) {
        var jsonText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importStatusMessage = ""
            },
            title = { Text("一键导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "请将导出的 JSON 字符串粘贴到下方：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        placeholder = { Text("在此粘贴内容...") }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                openFileLauncher.launch(arrayOf("application/json"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("从文件选择")
                        }
                        Button(
                            onClick = {
                                clipboardManager.getText()?.text?.let { jsonText = it }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("从剪贴板粘贴")
                        }
                    }
                    if (importStatusMessage.isNotEmpty()) {
                        Text(importStatusMessage, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.importData(jsonText) { success, msg ->
                            importStatusMessage = msg
                            if (success) {
                                showImportDialog = false
                                importStatusMessage = ""
                            }
                        }
                    },
                    enabled = jsonText.isNotBlank()
                ) {
                    Text("开始导入")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importStatusMessage = ""
                }) {
                    Text("取消")
                }
            }
        )
    }

    if (showAddBotDialog) {
        AddBotDialog(
            avatarDirUri = avatarDirUri,
            context = context,
            onDismiss = { showAddBotDialog = false },
            onPickImage = { callback ->
                onImagePicked = callback
                imagePickerLauncher.launch("image/*")
            },
            onConfirm = { name, token, avatar ->
                viewModel.addBot(name, token, avatar) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        showAddBotDialog = false
                    }
                }
            }
        )
    }

    if (showAddChatDialog) {
        AddChatDialog(
            avatarDirUri = avatarDirUri,
            context = context,
            onDismiss = { showAddChatDialog = false },
            onPickImage = { callback ->
                onImagePicked = callback
                imagePickerLauncher.launch("image/*")
            },
            onConfirm = { name, chatId, avatar ->
                viewModel.addChat(name, chatId, avatar) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        showAddChatDialog = false
                    }
                }
            }
        )
    }

    if (showMarkedAvatarsDialog) {
        val marks by viewModel.avatarMarks.collectAsState()
        MarkedAvatarsDialog(
            marks = marks,
            onDismiss = { showMarkedAvatarsDialog = false },
            onUnmark = { viewModel.toggleAvatarMark(it) },
            onClearAll = { viewModel.clearAllAvatarMarks() },
            moveTargetUri = readableAvatarMoveTargetUri,
            hasMoveTarget = avatarMoveTargetUri != null,
            onSelectMoveTarget = { openAvatarMoveTargetLauncher.launch(null) },
            onMoveSelected = { selected ->
                viewModel.moveMarkedAvatars(selected) { _, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

@Composable
fun BotList(
    viewModel: ConfigViewModel,
    avatarMoveTargetUri: String?,
    onEdit: (Bot) -> Unit,
    onDelete: (Bot) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bots by viewModel.bots.collectAsState()
    val marks by viewModel.avatarMarks.collectAsState()
    var previewAvatarUri by remember { mutableStateOf<String?>(null) }

    previewAvatarUri?.let { uri ->
        AvatarPreviewDialog(
            imageUri = uri,
            onDismiss = { previewAvatarUri = null }
        )
    }

    LazyColumn(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(bots) { bot ->
            val readableAvatarUri by rememberReadableAvatarUri(context, bot.avatarPath)
            val isMovedAvatar = remember(bot.avatarPath, avatarMoveTargetUri) {
                isUriInTree(bot.avatarPath, avatarMoveTargetUri)
            }
            val canMarkAvatar = readableAvatarUri != null && !isMovedAvatar
            val isMarked = canMarkAvatar && marks.any { it.uri == readableAvatarUri }
            BotCard(
                bot = bot,
                readableAvatarUri = readableAvatarUri,
                canMarkAvatar = canMarkAvatar,
                isMarked = isMarked,
                onClick = { onEdit(bot) },
                onAvatarClick = {
                    bot.avatarPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { avatarPath ->
                            scope.launch {
                                if (isReadableAvatarUri(context, avatarPath)) {
                                    previewAvatarUri = avatarPath
                                }
                            }
                        }
                },
                onToggleMark = {
                    bot.avatarPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { avatarPath ->
                            scope.launch {
                                if (isReadableAvatarUri(context, avatarPath)) {
                                    viewModel.toggleAvatarMark(avatarPath)
                                }
                            }
                        }
                },
                onDelete = { onDelete(bot) }
            )
        }
    }
}

@Composable
fun BotCard(
    bot: Bot,
    readableAvatarUri: String?,
    canMarkAvatar: Boolean,
    isMarked: Boolean,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onToggleMark: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.TopStart) {
                AsyncImage(
                    model = readableAvatarUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (readableAvatarUri != null) {
                                Modifier.clickable(onClick = onAvatarClick)
                            } else {
                                Modifier
                            }
                        ),
                    contentScale = ContentScale.Crop
                )
                if (canMarkAvatar && isMarked) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.White, androidx.compose.foundation.shape.CircleShape)
                            .padding(2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bot.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Token: ${bot.token.take(10)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                IconButton(
                    onClick = onToggleMark,
                    enabled = canMarkAvatar,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isMarked) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                        contentDescription = "Mark",
                        tint = when {
                            !canMarkAvatar -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            isMarked -> Color.Red
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun ChatList(
    viewModel: ConfigViewModel,
    avatarMoveTargetUri: String?,
    onEdit: (Chat) -> Unit,
    onDelete: (Chat) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chats by viewModel.chats.collectAsState()
    val marks by viewModel.avatarMarks.collectAsState()
    var previewAvatarUri by remember { mutableStateOf<String?>(null) }

    previewAvatarUri?.let { uri ->
        AvatarPreviewDialog(
            imageUri = uri,
            onDismiss = { previewAvatarUri = null }
        )
    }

    LazyColumn(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(chats) { chat ->
            val readableAvatarUri by rememberReadableAvatarUri(context, chat.avatarPath)
            val isMovedAvatar = remember(chat.avatarPath, avatarMoveTargetUri) {
                isUriInTree(chat.avatarPath, avatarMoveTargetUri)
            }
            val canMarkAvatar = readableAvatarUri != null && !isMovedAvatar
            val isMarked = canMarkAvatar && marks.any { it.uri == readableAvatarUri }
            ChatCard(
                chat = chat,
                readableAvatarUri = readableAvatarUri,
                canMarkAvatar = canMarkAvatar,
                isMarked = isMarked,
                onClick = { onEdit(chat) },
                onAvatarClick = {
                    chat.avatarPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { avatarPath ->
                            scope.launch {
                                if (isReadableAvatarUri(context, avatarPath)) {
                                    previewAvatarUri = avatarPath
                                }
                            }
                        }
                },
                onToggleMark = {
                    chat.avatarPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { avatarPath ->
                            scope.launch {
                                if (isReadableAvatarUri(context, avatarPath)) {
                                    viewModel.toggleAvatarMark(avatarPath)
                                }
                            }
                        }
                },
                onDelete = { onDelete(chat) }
            )
        }
    }
}

@Composable
fun ChatCard(
    chat: Chat,
    readableAvatarUri: String?,
    canMarkAvatar: Boolean,
    isMarked: Boolean,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onToggleMark: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.TopStart) {
                AsyncImage(
                    model = readableAvatarUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (readableAvatarUri != null) {
                                Modifier.clickable(onClick = onAvatarClick)
                            } else {
                                Modifier
                            }
                        ),
                    contentScale = ContentScale.Crop
                )
                if (canMarkAvatar && isMarked) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.White, androidx.compose.foundation.shape.CircleShape)
                            .padding(2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ID: ${chat.chatId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                IconButton(
                    onClick = onToggleMark,
                    enabled = canMarkAvatar,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isMarked) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                        contentDescription = "Mark",
                        tint = when {
                            !canMarkAvatar -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            isMarked -> Color.Red
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun SettingsTab(
    exportUri: String?,
    onSelectDirectory: () -> Unit,
    avatarDirUri: String?,
    onSelectAvatarDirectory: () -> Unit,
    avatarMoveTargetUri: String?,
    onSelectAvatarMoveTarget: () -> Unit,
    onShowMarkedAvatars: () -> Unit,
    modifier: Modifier = Modifier,
    context: android.content.Context
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PermissionGuideCard(
            context = context,
            onShowMarkedAvatars = onShowMarkedAvatars
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "导出设置", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "配置文件输出位置：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = exportUri ?: "未设置 (点击下方按钮选择目录)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exportUri == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSelectDirectory) {
                    Text(if (exportUri == null) "选择输出目录" else "更改输出目录")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "头像设置", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "默认头像资产目录：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = avatarDirUri ?: "未设置 (设置后可快速选择图片)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (avatarDirUri == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSelectAvatarDirectory) {
                    Text(if (avatarDirUri == null) "选择头像目录" else "更改头像目录")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "已标记头像移动目录：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = avatarMoveTargetUri ?: "未设置 (用于整理已标记头像)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (avatarMoveTargetUri == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSelectAvatarMoveTarget) {
                    Text(if (avatarMoveTargetUri == null) "选择移动目录" else "更改移动目录")
                }
            }
        }

        Text(
            text = "说明：设置头像目录后，在添加机器人或群组时，可以直接从该目录下的图片中挑选。设置移动目录后，已标记头像管理里可以直接整理选中的头像。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MarkedAvatarsDialog(
    marks: List<AvatarMark>,
    onDismiss: () -> Unit,
    onUnmark: (String) -> Unit,
    onClearAll: () -> Unit,
    moveTargetUri: String?,
    hasMoveTarget: Boolean,
    onSelectMoveTarget: () -> Unit,
    onMoveSelected: (Set<String>) -> Unit
) {
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(marks) {
        val currentUris = marks.map { it.uri }.toSet()
        selectedUris = selectedUris.intersect(currentUris)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已标记头像管理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (marks.isEmpty()) {
                    Text("暂无标记的头像", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "共标记了 ${marks.size} 个头像，已选 ${selectedUris.size} 个。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "移动目录：${moveTargetUri ?: "未设置"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasMoveTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = {
                            selectedUris = if (selectedUris.size == marks.size) {
                                emptySet()
                            } else {
                                marks.map { it.uri }.toSet()
                            }
                        }) {
                            Text(if (selectedUris.size == marks.size) "取消全选" else "全选")
                        }
                        TextButton(onClick = onSelectMoveTarget) {
                            Text(if (hasMoveTarget) "更改移动目录" else "选择移动目录")
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(marks, key = { it.uri }) { mark ->
                            val checked = selectedUris.contains(mark.uri)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedUris = if (checked) {
                                            selectedUris - mark.uri
                                        } else {
                                            selectedUris + mark.uri
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedUris = if (isChecked) {
                                            selectedUris + mark.uri
                                        } else {
                                            selectedUris - mark.uri
                                        }
                                    }
                                )
                                AsyncImage(
                                    model = mark.uri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = Uri.decode(mark.uri).substringAfterLast("/"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(onClick = {
                                    selectedUris = selectedUris - mark.uri
                                    onUnmark(mark.uri)
                                }) {
                                    Icon(
                                        Icons.Default.Close,
                                        null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (marks.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAll,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("清空标记")
                    }
                    Button(
                        onClick = { onMoveSelected(selectedUris) },
                        enabled = hasMoveTarget && selectedUris.isNotEmpty()
                    ) {
                        Text("移动选中")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun KeepAliveItem(
    title: String,
    desc: String,
    status: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Icon(
            imageVector = if (status) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (status) "已就绪" else "需要处理",
            tint = if (status) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun KeepAliveToggleItem(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun KeepAliveOverview(
    enabled: Boolean,
    running: Boolean,
    lastServiceStartedAt: Long,
    lastHeartbeatAt: Long,
    lastRecoveryAt: Long,
    nextDailyRecoveryAt: Long,
    nextTaskPreflightAt: Long,
    startError: String?
) {
    val now = System.currentTimeMillis()
    val heartbeatFresh = lastHeartbeatAt > 0L && now - lastHeartbeatAt <= 26L * 60L * 60L * 1000L
    val dailyRecoveryScheduled = nextDailyRecoveryAt > now
    val taskPreflightScheduled = nextTaskPreflightAt > now
    val healthy = enabled && running && heartbeatFresh && dailyRecoveryScheduled && startError == null
    val containerColor = if (healthy) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (healthy) Icons.Default.VerifiedUser else Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            healthy -> "守护链路运行正常"
                            !enabled -> "后台守护尚未开启"
                            !running -> "服务等待系统重新拉起"
                            else -> "守护链路需要检查"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "服务心跳 + 每日巡检 + 任务预恢复",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ) {
                    Text(
                        text = if (enabled) "已开启" else "未开启",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
            Spacer(Modifier.height(12.dp))

            DiagnosticLine("前台服务", if (running) "正在运行" else "未检测到")
            DiagnosticLine("最近启动", formatDiagnosticTime(lastServiceStartedAt))
            DiagnosticLine("最近心跳", formatDiagnosticTime(lastHeartbeatAt))
            DiagnosticLine("最近外部唤醒", formatDiagnosticTime(lastRecoveryAt))
            DiagnosticLine(
                "下次每日巡检",
                if (dailyRecoveryScheduled) formatDiagnosticTime(nextDailyRecoveryAt) else "尚未注册"
            )
            DiagnosticLine(
                "任务预恢复",
                if (taskPreflightScheduled) formatDiagnosticTime(nextTaskPreflightAt) else "暂无远期任务"
            )

            if (!startError.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "最近启动错误：$startError",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatDiagnosticTime(timeMillis: Long): String {
    if (timeMillis <= 0L) return "暂无记录"
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
}

@Composable
fun PermissionGuideCard(
    context: Context,
    onShowMarkedAvatars: () -> Unit
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var keepAliveEnabled by remember { mutableStateOf(KeepAliveService.isEnabled(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            keepAliveEnabled = KeepAliveService.isEnabled(context)
            refreshTick++
        }
    }

    val keepAliveRunning = KeepAliveService.isRunning
    val diagnostics = remember(refreshTick) { KeepAliveDiagnostics.snapshot(context) }
    val recoverySchedule = remember(refreshTick) { RecoveryScheduler.scheduleSnapshot(context) }
    val canScheduleAlarms = remember(refreshTick) { PermissionUtils.canScheduleExactAlarms(context) }
    val isBatteryIgnored = remember(refreshTick) { PermissionUtils.isBatteryOptimizationIgnored(context) }
    val notificationsEnabled = remember(refreshTick) {
        PermissionUtils.isNotificationChannelEnabled(
            context,
            KeepAliveService.NOTIFICATION_CHANNEL_ID
        )
    }
    val backgroundDataAllowed = remember(refreshTick) { PermissionUtils.isBackgroundDataAllowed(context) }
    val isXiaomi = PermissionUtils.isXiaomi()
    val isXiaomiAutostart = remember(refreshTick, isXiaomi) {
        isXiaomi && PermissionUtils.isXiaomiAutostartEnabled(context)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("后台可靠性", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isXiaomi) "小米设备 / MIUI 守护状态" else "Android 后台守护状态",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onShowMarkedAvatars) {
                    Icon(Icons.Default.Bookmarks, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("标记管理")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            KeepAliveOverview(
                enabled = keepAliveEnabled,
                running = keepAliveRunning,
                lastServiceStartedAt = diagnostics.lastServiceStartedAt,
                lastHeartbeatAt = diagnostics.lastHeartbeatAt,
                lastRecoveryAt = diagnostics.lastRecoveryAt,
                nextDailyRecoveryAt = recoverySchedule.nextDailyRecoveryAt,
                nextTaskPreflightAt = recoverySchedule.nextTaskPreflightAt,
                startError = diagnostics.lastStartError
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("系统保障", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            KeepAliveItem(
                title = "精确闹钟权限",
                desc = "每个定时任务都会注册准点闹钟，这是可靠触发的核心权限。",
                status = canScheduleAlarms,
                onClick = { PermissionUtils.openExactAlarmSettings(context) }
            )

            KeepAliveItem(
                title = "忽略电池优化",
                desc = "允许 AutoTG 在待机和灭屏时执行到点发送与恢复检查。",
                status = isBatteryIgnored,
                onClick = { PermissionUtils.requestIgnoreBatteryOptimizations(context) }
            )

            KeepAliveItem(
                title = "守护通知通道",
                desc = "前台服务必须持续显示通知；关闭该通道会显著降低存活率。",
                status = notificationsEnabled,
                onClick = {
                    PermissionUtils.openNotificationChannelSettings(
                        context,
                        KeepAliveService.NOTIFICATION_CHANNEL_ID
                    )
                }
            )

            KeepAliveItem(
                title = "后台网络访问",
                desc = "允许灭屏和流量节省模式下访问 Telegram Bot API。",
                status = backgroundDataAllowed,
                onClick = { PermissionUtils.openBackgroundDataSettings(context) }
            )

            if (isXiaomi) {
                Spacer(Modifier.height(8.dp))
                Text("MIUI 专项", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                KeepAliveItem(
                    title = "小米自启动权限",
                    desc = "必须开启；MIUI 不开放稳定读取接口，点入系统页后请人工确认。",
                    status = isXiaomiAutostart,
                    onClick = { PermissionUtils.openXiaomiAutostartSettings(context) }
                )

                KeepAliveItem(
                    title = "小米省电策略",
                    desc = "建议将 AutoTG 设置为“无限制/不限制”，避免 MIUI 拦截后台唤醒。",
                    status = isBatteryIgnored,
                    onClick = { PermissionUtils.openBatterySaverSettings(context) }
                )

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.PushPin, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "再到最近任务中长按 AutoTG 卡片并加锁，避免一键清理时被 MIUI 移除。不要在应用详情页点击“强行停止”。",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("守护开关", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            KeepAliveToggleItem(
                title = "前台守护服务",
                desc = "常驻通知维持进程优先级；系统每日低频巡检，并在任务前 30 分钟预恢复。",
                checked = keepAliveEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        KeepAliveService.setEnabled(context, false)
                        KeepAliveService.stop(context)
                        keepAliveEnabled = false
                    } else {
                        KeepAliveService.setEnabled(context, true)
                        val started = KeepAliveService.start(context)
                        keepAliveEnabled = started
                        if (!started) {
                            KeepAliveService.setEnabled(context, false)
                            Toast.makeText(context, "前台守护服务启动失败，请检查通知和后台权限", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "建议开启顺序：精确闹钟 → 忽略电池优化 → 通知与后台网络 → 小米自启动/无限制 → 前台守护服务。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "系统页无法直达时，请手动进入：设置 > 应用设置 > 应用管理 > AutoTG > 省电策略 / 自启动 / 通知管理。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { PermissionUtils.openAppDetailsSettings(context) }) {
                Text("打开 AutoTG 应用详情页")
            }
        }
    }
}

@Composable
private fun AvatarAssetPickerDialog(
    avatarDirUri: String,
    context: Context,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val assetState by produceState(
        initialValue = AvatarAssetLoadState(),
        avatarDirUri
    ) {
        val loaded = mutableListOf<String>()
        runCatching {
            withContext(Dispatchers.IO) {
                val treeUri = Uri.parse(avatarDirUri)
                val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    treeDocumentId
                )
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )

                context.contentResolver.query(
                    childrenUri,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val documentIdIndex = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID
                    )
                    val mimeTypeIndex = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )

                    while (cursor.moveToNext()) {
                        if (documentIdIndex < 0 || mimeTypeIndex < 0) continue

                        val mimeType = cursor.getString(mimeTypeIndex)
                        if (mimeType?.startsWith("image/") == true) {
                            val documentId = cursor.getString(documentIdIndex)
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                documentId
                            )
                            loaded += documentUri.toString()

                            if (loaded.size % AVATAR_ASSET_BATCH_SIZE == 0) {
                                value = AvatarAssetLoadState(
                                    images = loaded.toList(),
                                    isLoading = true
                                )
                            }
                        }
                    }
                }
            }
            value = AvatarAssetLoadState(
                images = loaded.toList(),
                isLoading = false
            )
        }.onFailure {
            value = AvatarAssetLoadState(
                images = loaded.toList(),
                isLoading = false,
                errorMessage = it.message ?: "读取头像目录失败"
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择本地头像资产") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    assetState.images.isEmpty() && assetState.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    assetState.images.isEmpty() -> {
                        Text(
                            text = assetState.errorMessage ?: "该目录下没有发现图片文件",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    else -> {
                        Text(
                            text = if (assetState.isLoading) {
                                "已加载 ${assetState.images.size} 张，继续扫描中..."
                            } else {
                                "已加载 ${assetState.images.size} 张"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.height(400.dp),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(assetState.images, key = { it }) { uri ->
                                val thumbnailRequest = remember(uri) {
                                    ImageRequest.Builder(context)
                                        .data(uri)
                                        .size(180)
                                        .crossfade(false)
                                        .build()
                                }
                                AsyncImage(
                                    model = thumbnailRequest,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { onSelect(uri) },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("返回") }
        }
    )
}

@Composable
fun AddBotDialog(
    initialBot: Bot? = null,
    avatarDirUri: String? = null,
    context: Context,
    onDismiss: () -> Unit,
    onPickImage: ((String) -> Unit) -> Unit,
    onConfirm: (String, String, String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialBot?.name ?: "") }
    var token by remember { mutableStateOf(initialBot?.token ?: "") }
    var avatarPath by remember { mutableStateOf(initialBot?.avatarPath ?: "") }
    var showAssetPicker by remember { mutableStateOf(false) }
    var previewAvatarUri by remember { mutableStateOf<String?>(null) }
    val readableAvatarPath by rememberReadableAvatarUri(context, avatarPath)

    previewAvatarUri?.let { uri ->
        AvatarPreviewDialog(
            imageUri = uri,
            onDismiss = { previewAvatarUri = null }
        )
    }

    if (showAssetPicker && avatarDirUri != null) {
        AvatarAssetPickerDialog(
            avatarDirUri = avatarDirUri,
            context = context,
            onSelect = { uri ->
                avatarPath = uri
                showAssetPicker = false
            },
            onDismiss = { showAssetPicker = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialBot == null) "添加机器人" else "编辑机器人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("机器人名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = if (avatarPath.isNullOrBlank()) "" else {
                        val decoded = Uri.decode(avatarPath)
                        decoded.substringAfterLast("/")
                    },
                    onValueChange = { /* Only update via pickers */ },
                    label = { Text("头像路径") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row {
                            if (avatarDirUri != null) {
                                IconButton(onClick = { showAssetPicker = true }) {
                                    Icon(Icons.Default.Folder, contentDescription = "资产库")
                                }
                            }
                            IconButton(onClick = {
                                onPickImage { path -> avatarPath = path }
                            }) {
                                Icon(Icons.Default.Image, contentDescription = "选择图片")
                            }
                        }
                    },
                    readOnly = true
                )
                if (readableAvatarPath != null) {
                    AsyncImage(
                        model = readableAvatarPath,
                        contentDescription = "预览",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .align(Alignment.CenterHorizontally)
                            .clickable {
                                scope.launch {
                                    if (isReadableAvatarUri(context, avatarPath)) {
                                        previewAvatarUri = avatarPath
                                    }
                                }
                            },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val confirmedAvatarPath = avatarPath
                            .takeIf { it.isNotBlank() && isReadableAvatarUri(context, it) }
                        onConfirm(name, token, confirmedAvatarPath)
                    }
                },
                enabled = name.isNotBlank() && token.isNotBlank()
            ) {
                Text(if (initialBot == null) "添加" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun AddChatDialog(
    initialChat: Chat? = null,
    avatarDirUri: String? = null,
    context: Context,
    onDismiss: () -> Unit,
    onPickImage: ((String) -> Unit) -> Unit,
    onConfirm: (String, String, String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialChat?.name ?: "") }
    var chatId by remember { mutableStateOf(initialChat?.chatId ?: "") }
    var avatarPath by remember { mutableStateOf(initialChat?.avatarPath ?: "") }
    var showAssetPicker by remember { mutableStateOf(false) }
    var previewAvatarUri by remember { mutableStateOf<String?>(null) }
    val readableAvatarPath by rememberReadableAvatarUri(context, avatarPath)

    previewAvatarUri?.let { uri ->
        AvatarPreviewDialog(
            imageUri = uri,
            onDismiss = { previewAvatarUri = null }
        )
    }

    if (showAssetPicker && avatarDirUri != null) {
        AvatarAssetPickerDialog(
            avatarDirUri = avatarDirUri,
            context = context,
            onSelect = { uri ->
                avatarPath = uri
                showAssetPicker = false
            },
            onDismiss = { showAssetPicker = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialChat == null) "添加群组/频道" else "编辑群组/频道") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Chat ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = if (avatarPath.isNullOrBlank()) "" else {
                        val decoded = Uri.decode(avatarPath)
                        decoded.substringAfterLast("/")
                    },
                    onValueChange = { /* Only update via pickers */ },
                    label = { Text("头像路径") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row {
                            if (avatarDirUri != null) {
                                IconButton(onClick = { showAssetPicker = true }) {
                                    Icon(Icons.Default.Folder, contentDescription = "资产库")
                                }
                            }
                            IconButton(onClick = {
                                onPickImage { path -> avatarPath = path }
                            }) {
                                Icon(Icons.Default.Image, contentDescription = "选择图片")
                            }
                        }
                    },
                    readOnly = true
                )
                if (readableAvatarPath != null) {
                    AsyncImage(
                        model = readableAvatarPath,
                        contentDescription = "预览",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .align(Alignment.CenterHorizontally)
                            .clickable {
                                scope.launch {
                                    if (isReadableAvatarUri(context, avatarPath)) {
                                        previewAvatarUri = avatarPath
                                    }
                                }
                            },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val confirmedAvatarPath = avatarPath
                            .takeIf { it.isNotBlank() && isReadableAvatarUri(context, it) }
                        onConfirm(name, chatId, confirmedAvatarPath)
                    }
                },
                enabled = name.isNotBlank() && chatId.isNotBlank()
            ) {
                Text(if (initialChat == null) "添加" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
