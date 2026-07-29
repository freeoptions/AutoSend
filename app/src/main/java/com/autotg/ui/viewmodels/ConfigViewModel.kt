package com.autotg.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotg.data.models.AvatarMark
import com.autotg.data.models.BackupData
import com.autotg.data.models.Bot
import com.autotg.data.models.BotBackup
import com.autotg.data.models.Chat
import com.autotg.data.models.ChatBackup
import com.autotg.data.models.MessageParseMode
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskBackup
import com.autotg.data.repository.TelegramRepository
import com.autotg.ui.components.isMarkableAvatarUri
import com.autotg.utils.CronUtils
import com.autotg.utils.SchedulerRecovery
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

private const val PREFS_NAME = "autotg_prefs"
private const val PREF_EXPORT_URI = "export_uri"
private const val PREF_AVATAR_DIR_URI = "avatar_dir_uri"
private const val PREF_AVATAR_MOVE_TARGET_URI = "avatar_move_target_uri"
private const val EXPORT_FILE_PATTERN = "AutoTG_exportConfig_%s.json"

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val repository: TelegramRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _exportUri = MutableStateFlow(prefs.getString(PREF_EXPORT_URI, null))
    val exportUri = _exportUri.asStateFlow()

    private val _avatarDirUri = MutableStateFlow(prefs.getString(PREF_AVATAR_DIR_URI, null))
    val avatarDirUri = _avatarDirUri.asStateFlow()

    private val _avatarMoveTargetUri = MutableStateFlow(prefs.getString(PREF_AVATAR_MOVE_TARGET_URI, null))
    val avatarMoveTargetUri = _avatarMoveTargetUri.asStateFlow()

    val bots: StateFlow<List<Bot>> = repository.getAllBots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chats: StateFlow<List<Chat>> = repository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val avatarMarks: StateFlow<List<AvatarMark>> = repository.getAllAvatarMarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleAvatarMark(uri: String) {
        viewModelScope.launch {
            val moveTargetUri = _avatarMoveTargetUri.value
            val isMarked = repository.isAvatarMarked(uri).first()
            val isMarkable = isMarkableAvatarUri(context, uri, moveTargetUri)
            if (!isMarkable) {
                if (isMarked) {
                    repository.deleteAvatarMark(AvatarMark(uri))
                }
                return@launch
            }

            if (isMarked) {
                repository.deleteAvatarMark(AvatarMark(uri))
            } else {
                repository.insertAvatarMark(AvatarMark(uri))
            }
        }
    }

    fun cleanupStaleAvatarMarks() {
        viewModelScope.launch {
            val moveTargetUri = _avatarMoveTargetUri.value
            repository.getAllAvatarMarks()
                .first()
                .forEach { mark ->
                    if (!isMarkableAvatarUri(context, mark.uri, moveTargetUri)) {
                        repository.deleteAvatarMark(mark)
                    }
                }
        }
    }

    fun clearAllAvatarMarks() {
        viewModelScope.launch {
            repository.clearAllAvatarMarks()
        }
    }

    fun saveExportUri(uri: String?) {
        _exportUri.value = uri
        prefs.edit().putString(PREF_EXPORT_URI, uri).apply()
    }

    fun saveAvatarDirUri(uri: String?) {
        _avatarDirUri.value = uri
        prefs.edit().putString(PREF_AVATAR_DIR_URI, uri).apply()
    }

    fun saveAvatarMoveTargetUri(uri: String?) {
        _avatarMoveTargetUri.value = uri
        prefs.edit().putString(PREF_AVATAR_MOVE_TARGET_URI, uri).apply()
    }

    fun moveMarkedAvatars(
        uris: Set<String>,
        onComplete: (Boolean, String) -> Unit
    ) {
        val targetUri = _avatarMoveTargetUri.value
        if (uris.isEmpty()) {
            onComplete(false, "\u8bf7\u5148\u9009\u62e9\u8981\u79fb\u52a8\u7684\u5934\u50cf")
            return
        }
        if (targetUri.isNullOrBlank()) {
            onComplete(false, "\u8bf7\u5148\u8bbe\u7f6e\u6807\u8bb0\u5934\u50cf\u79fb\u52a8\u76ee\u5f55")
            return
        }

        viewModelScope.launch {
            var movedCount = 0
            var failedCount = 0
            var copyOnlyCount = 0
            val errors = mutableListOf<String>()
            val allBots = repository.getAllBots().first()
            val allChats = repository.getAllChats().first()

            uris.forEach { sourceUri ->
                val moveResult = withContext(Dispatchers.IO) {
                    runCatching { moveAvatarFile(sourceUri, targetUri) }
                }

                val fileResult = moveResult.getOrNull()
                if (fileResult == null) {
                    failedCount++
                    errors += moveResult.exceptionOrNull()?.message ?: "\u672a\u77e5\u9519\u8bef"
                    return@forEach
                }

                allBots.filter { it.avatarPath == sourceUri }
                    .forEach { repository.updateBot(it.copy(avatarPath = null)) }

                allChats.filter { it.avatarPath == sourceUri }
                    .forEach { repository.updateChat(it.copy(avatarPath = null)) }

                repository.deleteAvatarMark(AvatarMark(sourceUri))
                movedCount++
                if (!fileResult.sourceDeleted) {
                    copyOnlyCount++
                }
            }

            val message = buildString {
                append("\u5df2\u79fb\u52a8 $movedCount \u4e2a\u5934\u50cf")
                if (copyOnlyCount > 0) {
                    append("\uff0c\u5176\u4e2d $copyOnlyCount \u4e2a\u539f\u6587\u4ef6\u65e0\u5220\u9664\u6743\u9650\uff0c\u5df2\u590d\u5236\u5230\u76ee\u6807\u76ee\u5f55")
                }
                if (failedCount > 0) {
                    append("\uff0c$failedCount \u4e2a\u5931\u8d25")
                    errors.firstOrNull()?.let { append("\uff1a$it") }
                }
            }
            onComplete(movedCount > 0, message)
        }
    }

    fun performFileExport(json: String, onComplete: (Boolean, String) -> Unit) {
        val uriString = _exportUri.value
        if (uriString == null) {
            onComplete(false, "\u672a\u8bbe\u7f6e\u5bfc\u51fa\u76ee\u5f55")
            return
        }

        viewModelScope.launch {
            try {
                val directoryUri = Uri.parse(uriString)
                val directory = DocumentFile.fromTreeUri(context, directoryUri)
                if (directory == null || !directory.canWrite()) {
                    onComplete(false, "\u76ee\u5f55\u4e0d\u53ef\u5199\u6216\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u8bbe\u7f6e")
                    return@launch
                }

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH_mm_ss", Locale.getDefault()).format(Date())
                val fileName = EXPORT_FILE_PATTERN.format(timestamp)
                val file = directory.createFile("application/json", fileName)

                if (file == null) {
                    onComplete(false, "\u65e0\u6cd5\u521b\u5efa\u6587\u4ef6")
                    return@launch
                }

                context.contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                }
                onComplete(true, "\u914d\u7f6e\u5df2\u5bfc\u51fa\u5230\u6307\u5b9a\u4f4d\u7f6e")
            } catch (e: Exception) {
                onComplete(false, "\u5bfc\u51fa\u6587\u4ef6\u5931\u8d25: ${e.message}")
            }
        }
    }

    fun exportData(onComplete: (String) -> Unit) {
        viewModelScope.launch {
            val allBots = repository.getAllBots().first()
            val allChats = repository.getAllChats().first()
            val allTasks = repository.getAllTasks().first()

            val backup = BackupData(
                bots = allBots.map { BotBackup(it.name, it.token) },
                chats = allChats.map { ChatBackup(it.name, it.chatId) },
                tasks = allTasks.map { task ->
                    TaskBackup(
                        botName = allBots.find { it.id == task.botId }?.name ?: "",
                        chatName = allChats.find { it.id == task.chatId }?.name ?: "",
                        content = task.content,
                        parseMode = task.parseMode.name,
                        scheduledTime = task.scheduledTime,
                        isEnabled = task.isEnabled,
                        cronExpression = task.cronExpression
                    )
                }
            )
            onComplete(Gson().toJson(backup))
        }
    }

    fun importData(json: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val backup = Gson().fromJson(json, BackupData::class.java)
                    ?: throw Exception("\u89e3\u6790\u5931\u8d25")

                val botMap = mutableMapOf<String, Long>()
                backup.bots.forEach { botBackup ->
                    val existingBot = repository.getBotByToken(botBackup.token)
                    val id = existingBot?.id ?: repository.insertBot(
                        Bot(name = botBackup.name, token = botBackup.token)
                    )
                    botMap[botBackup.name] = id
                }

                val chatMap = mutableMapOf<String, Long>()
                backup.chats.forEach { chatBackup ->
                    val existingChat = repository.getChatByChatId(chatBackup.chatId)
                    val id = existingChat?.id ?: repository.insertChat(
                        Chat(name = chatBackup.name, chatId = chatBackup.chatId)
                    )
                    chatMap[chatBackup.name] = id
                }

                val now = System.currentTimeMillis()
                var skippedTaskCount = 0
                var duplicateTaskCount = 0

                backup.tasks.forEach { taskBackup ->
                    val botId = botMap[taskBackup.botName]
                    val chatId = chatMap[taskBackup.chatName]
                    if (botId != null && chatId != null) {
                        val cron = taskBackup.cronExpression?.takeIf { it.isNotBlank() }
                        val scheduledTime = when {
                            cron != null && taskBackup.scheduledTime <= now ->
                                CronUtils.getNextExecutionTimes(cron, 1).firstOrNull()

                            taskBackup.scheduledTime > 0L -> taskBackup.scheduledTime
                            else -> null
                        }

                        if (scheduledTime == null) {
                            skippedTaskCount++
                            return@forEach
                        }

                        val parseMode = runCatching {
                            MessageParseMode.valueOf(taskBackup.parseMode ?: MessageParseMode.NONE.name)
                        }.getOrDefault(MessageParseMode.NONE)

                        val duplicateTask = repository.findDuplicateTask(
                            botId = botId,
                            chatId = chatId,
                            content = taskBackup.content,
                            parseMode = parseMode.name,
                            scheduledTime = scheduledTime,
                            isEnabled = taskBackup.isEnabled,
                            cronExpression = cron
                        )
                        if (duplicateTask != null) {
                            duplicateTaskCount++
                            return@forEach
                        }

                        repository.insertTask(
                            ScheduledTask(
                                botId = botId,
                                chatId = chatId,
                                content = taskBackup.content,
                                parseMode = parseMode,
                                scheduledTime = scheduledTime,
                                isEnabled = taskBackup.isEnabled,
                                cronExpression = cron
                            )
                        )
                    }
                }

                val message = buildString {
                    append("\u5bfc\u5165\u6210\u529f")
                    if (skippedTaskCount > 0) {
                        append("\uff0c\u5df2\u8df3\u8fc7 $skippedTaskCount \u6761\u65f6\u95f4\u65e0\u6548\u7684\u5355\u6b21\u4efb\u52a1")
                    }
                    if (duplicateTaskCount > 0) {
                        append("\uff0c\u5df2\u8df3\u8fc7 $duplicateTaskCount \u6761\u91cd\u590d\u4efb\u52a1")
                    }
                }
                SchedulerRecovery.recoverEnabledTasks(context, repository)
                onComplete(true, message)
            } catch (e: Exception) {
                onComplete(false, "\u5bfc\u5165\u5931\u8d25: ${e.message}")
            }
        }
    }

    fun addBot(
        name: String,
        token: String,
        avatarPath: String? = null,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedToken = token.trim()
            val existingBot = repository.getBotByToken(normalizedToken)
            if (existingBot != null) {
                onComplete(false, "\u8be5\u673a\u5668\u4eba Token \u5df2\u5b58\u5728\uff0c\u4e0d\u80fd\u91cd\u590d\u6dfb\u52a0")
                return@launch
            }
            repository.insertBot(Bot(name = name, token = normalizedToken, avatarPath = avatarPath))
            onComplete(true, "\u673a\u5668\u4eba\u5df2\u6dfb\u52a0")
        }
    }

    fun deleteBot(bot: Bot) {
        viewModelScope.launch {
            repository.deleteBot(bot)
        }
    }

    fun addChat(
        name: String,
        chatId: String,
        avatarPath: String? = null,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedChatId = chatId.trim()
            val existingChat = repository.getChatByChatId(normalizedChatId)
            if (existingChat != null) {
                onComplete(false, "\u8be5\u7fa4\u7ec4/\u9891\u9053 ID \u5df2\u5b58\u5728\uff0c\u4e0d\u80fd\u91cd\u590d\u6dfb\u52a0")
                return@launch
            }
            repository.insertChat(Chat(name = name, chatId = normalizedChatId, avatarPath = avatarPath))
            onComplete(true, "\u7fa4\u7ec4/\u9891\u9053\u5df2\u6dfb\u52a0")
        }
    }

    fun deleteChat(chat: Chat) {
        viewModelScope.launch {
            repository.deleteChat(chat)
        }
    }

    fun updateBot(
        bot: Bot,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val normalizedToken = bot.token.trim()
            val existingBot = repository.getBotByToken(normalizedToken)
            if (existingBot != null && existingBot.id != bot.id) {
                onComplete(false, "\u8be5\u673a\u5668\u4eba Token \u5df2\u5b58\u5728\uff0c\u4e0d\u80fd\u91cd\u590d\u4fdd\u5b58")
                return@launch
            }
            repository.updateBot(bot.copy(token = normalizedToken))
            onComplete(true, "\u673a\u5668\u4eba\u5df2\u4fdd\u5b58")
        }
    }

    fun updateChat(
        chat: Chat,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val normalizedChatId = chat.chatId.trim()
            val existingChat = repository.getChatByChatId(normalizedChatId)
            if (existingChat != null && existingChat.id != chat.id) {
                onComplete(false, "\u8be5\u7fa4\u7ec4/\u9891\u9053 ID \u5df2\u5b58\u5728\uff0c\u4e0d\u80fd\u91cd\u590d\u4fdd\u5b58")
                return@launch
            }
            repository.updateChat(chat.copy(chatId = normalizedChatId))
            onComplete(true, "\u7fa4\u7ec4/\u9891\u9053\u5df2\u4fdd\u5b58")
        }
    }

    private data class AvatarMoveFileResult(
        val uri: String,
        val sourceDeleted: Boolean
    )

    private fun moveAvatarFile(
        sourceUriString: String,
        targetDirUriString: String
    ): AvatarMoveFileResult {
        val sourceUri = Uri.parse(sourceUriString)
        val targetDir = DocumentFile.fromTreeUri(context, Uri.parse(targetDirUriString))
            ?: error("\u79fb\u52a8\u76ee\u5f55\u65e0\u6548\uff0c\u8bf7\u91cd\u65b0\u8bbe\u7f6e")

        if (!targetDir.canWrite()) {
            error("\u79fb\u52a8\u76ee\u5f55\u4e0d\u53ef\u5199\uff0c\u8bf7\u91cd\u65b0\u8bbe\u7f6e")
        }

        val mimeType = context.contentResolver.getType(sourceUri) ?: "image/jpeg"
        val displayName = queryDisplayName(sourceUri)
            ?: DocumentFile.fromSingleUri(context, sourceUri)?.name
            ?: "avatar_${UUID.randomUUID()}.jpg"
        val targetName = uniqueFileName(targetDir, displayName)
        val targetFile = targetDir.createFile(mimeType, targetName)
            ?: error("\u521b\u5efa\u76ee\u6807\u6587\u4ef6\u5931\u8d25")

        val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                input.copyTo(output)
                true
            }
        } == true

        if (!copied) {
            targetFile.delete()
            error("\u590d\u5236\u5934\u50cf\u5931\u8d25")
        }

        val sourceFile = DocumentFile.fromSingleUri(context, sourceUri)
        val sourceDeleted = sourceFile?.delete() == true

        return AvatarMoveFileResult(
            uri = targetFile.uri.toString(),
            sourceDeleted = sourceDeleted
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }

    private fun uniqueFileName(directory: DocumentFile, originalName: String): String {
        val cleanName = originalName
            .substringAfterLast("/")
            .substringAfterLast("\\")
            .ifBlank { "avatar_${UUID.randomUUID()}.jpg" }

        val dotIndex = cleanName.lastIndexOf('.')
        val base = if (dotIndex > 0) cleanName.substring(0, dotIndex) else cleanName
        val ext = if (dotIndex > 0) cleanName.substring(dotIndex) else ""

        var candidate = cleanName
        var index = 1
        while (directory.findFile(candidate) != null) {
            candidate = "${base}_$index$ext"
            index++
        }
        return candidate
    }
}
