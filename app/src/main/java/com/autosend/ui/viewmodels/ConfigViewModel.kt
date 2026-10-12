package com.autosend.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autosend.data.models.BackupData
import com.autosend.data.models.Bot
import com.autosend.data.models.Chat
import com.autosend.data.models.BotBackup
import com.autosend.data.models.ChatBackup
import com.autosend.data.models.TelegramTargetConfig
import com.autosend.utils.TelegramQueueScheduler
import com.autosend.data.models.DeliveryChannel
import com.autosend.data.models.FeishuWebhook
import com.autosend.data.models.FeishuWebhookBackup
import com.autosend.data.models.QqBot
import com.autosend.data.models.QqBotBackup
import com.autosend.data.models.MessageParseMode
import com.autosend.data.models.ScheduledTask
import com.autosend.data.models.TaskBackup
import com.autosend.data.repository.TelegramRepository
import com.autosend.utils.RecurringScheduleUtils
import com.autosend.utils.LunarCalendarUtils
import com.autosend.utils.SchedulerRecovery
import com.autosend.utils.WorkManagerHelper
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val PREFS_NAME = "autosend_prefs"
private const val PREF_EXPORT_URI = "export_uri"
private const val EXPORT_FILE_PATTERN = "AutoSend_exportConfig_%s.json"

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val repository: TelegramRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _exportUri = MutableStateFlow(prefs.getString(PREF_EXPORT_URI, null))
    val exportUri = _exportUri.asStateFlow()

    val telegramTargets = combine(repository.getAllBots(), repository.getAllChats()) { bots, chats ->
        bots.flatMap { bot -> chats.map { chat -> TelegramTargetConfig(bot, chat) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendingMessages = repository.getPendingMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendingCount = repository.getPendingMessageCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val deliveryGates = repository.getDeliveryGates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val queueTasks = repository.getAllTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveTelegramTarget(initial: TelegramTargetConfig?, botName: String, token: String,
                           chatName: String, chatId: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (botName.isBlank() || chatName.isBlank() ||
                !Regex("[0-9]+:[A-Za-z0-9_-]+").matches(token.trim()) ||
                (chatId.trim().toLongOrNull()?.let { it != 0L } != true && !Regex("@[A-Za-z0-9_]+").matches(chatId.trim()))) {
                onComplete(false, "请填写名称、有效的 Bot Token 和 Chat ID（群组 ID 通常为负数）")
                return@launch
            }
            val duplicateBot = repository.getBotByToken(token.trim())
            val duplicateChat = repository.getChatByChatId(chatId.trim())
            if (repository.getAllBots().first().any { it.name == botName.trim() && it.id != (initial?.bot?.id ?: duplicateBot?.id) } ||
                repository.getAllChats().first().any { it.name == chatName.trim() && it.id != (initial?.chat?.id ?: duplicateChat?.id) }) {
                onComplete(false, "机器人名称和目标名称不能与其他配置重复")
                return@launch
            }
            if (initial != null && ((duplicateBot != null && duplicateBot.id != initial.bot.id) ||
                    (duplicateChat != null && duplicateChat.id != initial.chat.id))) {
                onComplete(false, "该机器人或目标已存在，请编辑已有配置")
                return@launch
            }
            val savedBotId: Long
            val savedChatId: Long
            if (initial == null) {
                savedBotId = duplicateBot?.id ?: repository.insertBot(Bot(name = botName.trim(), token = token.trim()))
                savedChatId = duplicateChat?.id ?: repository.insertChat(Chat(name = chatName.trim(), chatId = chatId.trim()))
            } else {
                repository.updateBot(initial.bot.copy(name = botName.trim(), token = token.trim()))
                repository.updateChat(initial.chat.copy(name = chatName.trim(), chatId = chatId.trim()))
                savedBotId = initial.bot.id
                savedChatId = initial.chat.id
            }
            repository.queueDao.resetTargetGates(savedBotId, savedChatId)
            TelegramQueueScheduler.kick(context)
            onComplete(true, "Telegram 配置已保存")
        }
    }

    fun deleteTelegramTarget(target: TelegramTargetConfig) {
        viewModelScope.launch { repository.deleteChat(target.chat) }
    }

    fun testTelegramTarget(target: TelegramTargetConfig, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = repository.telegramClient.test(target.bot.id, target.chat.id)
            onComplete(result.isSuccess, result.fold({ "机器人与目标连接正常" }, { it.message ?: "连接测试失败" }))
        }
    }

    fun retryQueue() { viewModelScope.launch {
        repository.retryPendingMessages()
        repository.pauseDelivery(false)
        TelegramQueueScheduler.kick(context)
    } }

    fun pauseQueue(paused: Boolean) { viewModelScope.launch {
        repository.pauseDelivery(paused)
        if (!paused) TelegramQueueScheduler.kick(context)
    } }

    fun retryPendingMessage(id: Long) { viewModelScope.launch {
        repository.retryPendingMessage(id)
        TelegramQueueScheduler.kick(context)
    } }

    fun cancelPendingMessage(id: Long) { viewModelScope.launch { repository.cancelPendingMessage(id) } }
    fun editPendingMessage(id: Long, content: String) { viewModelScope.launch {
        repository.editPendingMessage(id, content)
        repository.retryPendingMessage(id)
        TelegramQueueScheduler.kick(context)
    } }

    val feishuWebhooks: kotlinx.coroutines.flow.StateFlow<List<FeishuWebhook>> =
        repository.getAllFeishuWebhooks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val qqBots: kotlinx.coroutines.flow.StateFlow<List<QqBot>> =
        repository.getAllQqBots()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveExportUri(uri: String?) {
        _exportUri.value = uri
        prefs.edit().putString(PREF_EXPORT_URI, uri).apply()
    }

    fun performFileExport(json: String, onComplete: (Boolean, String) -> Unit) {
        val uriString = _exportUri.value
        if (uriString == null) {
            onComplete(false, "未设置导出目录")
            return
        }

        viewModelScope.launch {
            try {
                val directory = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
                if (directory == null || !directory.canWrite()) {
                    onComplete(false, "目录不可写或已失效，请重新设置")
                    return@launch
                }

                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH_mm_ss",
                    Locale.getDefault()
                ).format(Date())
                val file = directory.createFile(
                    "application/json",
                    EXPORT_FILE_PATTERN.format(timestamp)
                )
                if (file == null) {
                    onComplete(false, "无法创建文件")
                    return@launch
                }

                context.contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                }
                onComplete(true, "配置已导出到指定位置")
            } catch (e: Exception) {
                onComplete(false, "导出文件失败：${e.message}")
            }
        }
    }

    fun exportData(onComplete: (String) -> Unit) {
        viewModelScope.launch {
            val webhooks = repository.getAllFeishuWebhooks().first()
            val qqBots = repository.getAllQqBots().first()
            val bots = repository.getAllBots().first()
            val chats = repository.getAllChats().first()
            val tasks = repository.getAllTasks().first()
            val backup = BackupData(
                bots = bots.map { BotBackup(it.name, it.token) },
                chats = chats.map { ChatBackup(it.name, it.chatId) },
                feishuWebhooks = webhooks.map {
                    FeishuWebhookBackup(it.name, it.webhookUrl, it.secret)
                },
                qqBots = qqBots.map {
                    QqBotBackup(it.name, it.appId, it.clientSecret, it.groupOpenId)
                },
                tasks = tasks.map { task ->
                    TaskBackup(
                        botName = bots.find { it.id == task.botId }?.name.orEmpty(),
                        chatName = chats.find { it.id == task.chatId }?.name.orEmpty(),
                        deliveryChannel = task.deliveryChannel.name,
                        feishuWebhookName = if (task.deliveryChannel == DeliveryChannel.FEISHU) {
                            webhooks.find { it.id == task.feishuWebhookId }?.name
                        } else {
                            null
                        },
                        qqBotName = if (task.deliveryChannel == DeliveryChannel.QQ) {
                            qqBots.find { it.id == task.qqBotId }?.name
                        } else {
                            null
                        },
                        content = task.content,
                        parseMode = task.parseMode.name,
                        scheduledTime = task.scheduledTime,
                        isEnabled = task.isEnabled,
                        cronExpression = task.cronExpression,
                        lunarMonth = task.lunarMonth,
                        lunarDay = task.lunarDay,
                        lunarLeapMonth = task.lunarLeapMonth
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
                    ?: throw Exception("解析失败")

                val botMap = mutableMapOf<String, Long>()
                backup.bots.orEmpty().forEach { saved ->
                    require(saved.name.isNotBlank() && Regex("[0-9]+:[A-Za-z0-9_-]+").matches(saved.token.trim())) { "Telegram 机器人配置无效" }
                    botMap[saved.name.trim()] = repository.getBotByToken(saved.token.trim())?.id
                        ?: repository.insertBot(Bot(name = saved.name.trim(), token = saved.token.trim()))
                }
                val chatMap = mutableMapOf<String, Long>()
                backup.chats.orEmpty().forEach { saved ->
                    require(saved.name.isNotBlank() && saved.chatId.isNotBlank()) { "Telegram 目标配置无效" }
                    chatMap[saved.name.trim()] = repository.getChatByChatId(saved.chatId.trim())?.id
                        ?: repository.insertChat(Chat(name = saved.name.trim(), chatId = saved.chatId.trim()))
                }
                // 转换后的任务 JSON 可以仅引用本机已有目标，不必再次携带 Token。
                repository.getAllBots().first().forEach { botMap.putIfAbsent(it.name, it.id) }
                repository.getAllChats().first().forEach { chatMap.putIfAbsent(it.name, it.id) }

                backup.tasks.filter { it.migrateFromChannel != null }.forEach { task ->
                    require(task.deliveryChannel == DeliveryChannel.TELEGRAM.name &&
                        task.migrateFromChannel == DeliveryChannel.QQ.name) { "不支持的任务迁移方式" }
                    require((botMap[task.botName.orEmpty().trim()] != null ||
                        (task.botName.isNullOrBlank() && botMap.values.distinct().size == 1)) &&
                        (chatMap[task.chatName.orEmpty().trim()] != null ||
                        (task.chatName.isNullOrBlank() && chatMap.values.distinct().size == 1))) {
                        "请先配置唯一的 Telegram 机器人和目标，再导入转换文件；多个目标时需填写 botName 和 chatName"
                    }
                }

                val webhookMap = mutableMapOf<String, Long>()
                var skippedWebhookCount = 0
                backup.feishuWebhooks.orEmpty().forEach { webhookBackup ->
                    val normalizedName = webhookBackup.name.trim()
                    val normalizedUrl = webhookBackup.webhookUrl.trim()
                    if (normalizedName.isEmpty() || repository.validateFeishuWebhookUrl(normalizedUrl) != null) {
                        skippedWebhookCount++
                        return@forEach
                    }
                    val existing = repository.getFeishuWebhookByUrl(normalizedUrl)
                    val id = existing?.id ?: repository.insertFeishuWebhook(
                        FeishuWebhook(
                            name = normalizedName,
                            webhookUrl = normalizedUrl,
                            secret = webhookBackup.secret?.trim()?.takeIf { it.isNotEmpty() }
                        )
                    )
                    webhookMap[normalizedName] = id
                }

                val qqBotMap = mutableMapOf<String, Long>()
                var skippedQqBotCount = 0
                backup.qqBots.orEmpty().forEach { botBackup ->
                    val normalizedName = botBackup.name.trim()
                    val normalizedAppId = botBackup.appId.trim()
                    val normalizedSecret = botBackup.clientSecret.trim()
                    val normalizedGroupOpenId = botBackup.groupOpenId.trim()
                    val normalizedBot = QqBot(
                        name = normalizedName,
                        appId = normalizedAppId,
                        clientSecret = normalizedSecret,
                        groupOpenId = normalizedGroupOpenId
                    )
                    if (repository.validateQqBot(normalizedBot) != null) {
                        skippedQqBotCount++
                        return@forEach
                    }
                    val existing = repository.getQqBotByTarget(normalizedAppId, normalizedGroupOpenId)
                    val id = existing?.id ?: repository.insertQqBot(normalizedBot)
                    qqBotMap[normalizedName] = id
                }
                repository.getAllQqBots().first().forEach { qqBotMap.putIfAbsent(it.name, it.id) }

                val now = System.currentTimeMillis()
                var skippedUnsupportedTaskCount = 0
                var skippedTaskCount = 0
                var duplicateTaskCount = 0
                var migratedTaskCount = 0
                backup.tasks.forEach { taskBackup ->
                    val channel = when (taskBackup.deliveryChannel?.uppercase(Locale.ROOT)) {
                        DeliveryChannel.FEISHU.name -> DeliveryChannel.FEISHU
                        DeliveryChannel.QQ.name -> DeliveryChannel.QQ
                        DeliveryChannel.TELEGRAM.name, null -> DeliveryChannel.TELEGRAM
                        else -> null
                    }
                    if (channel == null) {
                        skippedUnsupportedTaskCount++
                        return@forEach
                    }

                    val webhookId = if (channel == DeliveryChannel.FEISHU) {
                        taskBackup.feishuWebhookName?.trim()?.let { webhookMap[it] }
                    } else {
                        null
                    }
                    val qqBotId = if (channel == DeliveryChannel.QQ) {
                        taskBackup.qqBotName?.trim()?.let { qqBotMap[it] }
                    } else {
                        null
                    }
                    val botName = taskBackup.botName.orEmpty().trim()
                    val chatName = taskBackup.chatName.orEmpty().trim()
                    val botId = if (channel == DeliveryChannel.TELEGRAM) {
                        botMap[botName] ?: if (botName.isBlank()) botMap.values.distinct().singleOrNull() else null
                    } else null
                    val chatId = if (channel == DeliveryChannel.TELEGRAM) {
                        chatMap[chatName] ?: if (chatName.isBlank()) chatMap.values.distinct().singleOrNull() else null
                    } else null
                    if ((channel == DeliveryChannel.FEISHU && webhookId == null) ||
                        (channel == DeliveryChannel.QQ && qqBotId == null) ||
                        (channel == DeliveryChannel.TELEGRAM && (botId == null || chatId == null))
                    ) {
                        skippedTaskCount++
                        return@forEach
                    }

                    val cron = taskBackup.cronExpression?.takeIf { it.isNotBlank() }
                    val lunarMonth = taskBackup.lunarMonth
                    val lunarDay = taskBackup.lunarDay
                    val lunarLeapMonth = taskBackup.lunarLeapMonth
                    val hasPartialLunarDate = (lunarMonth == null) != (lunarDay == null)
                    if (hasPartialLunarDate || (cron != null && lunarMonth != null)) {
                        skippedTaskCount++
                        return@forEach
                    }
                    if (lunarMonth != null && !LunarCalendarUtils.isValidDate(lunarMonth, lunarDay)) {
                        skippedTaskCount++
                        return@forEach
                    }

                    val timeSeed = taskBackup.scheduledTime.takeIf { it > 0L } ?: now
                    val importedTask = ScheduledTask(
                        deliveryChannel = channel,
                        botId = botId,
                        chatId = chatId,
                        feishuWebhookId = webhookId,
                        qqBotId = qqBotId,
                        content = taskBackup.content,
                        parseMode = runCatching { MessageParseMode.valueOf(taskBackup.parseMode ?: "NONE") }.getOrDefault(MessageParseMode.NONE),
                        scheduledTime = timeSeed,
                        isEnabled = taskBackup.isEnabled,
                        cronExpression = cron,
                        lunarMonth = lunarMonth,
                        lunarDay = lunarDay,
                        lunarLeapMonth = lunarLeapMonth
                    )
                    val scheduledTime = when {
                        channel != DeliveryChannel.TELEGRAM && RecurringScheduleUtils.isRecurring(importedTask) &&
                            taskBackup.scheduledTime <= now ->
                            RecurringScheduleUtils.getNextExecutionTimeFrom(importedTask, now + 1_000L)
                        taskBackup.scheduledTime > 0L -> taskBackup.scheduledTime
                        else -> null
                    }
                    if (scheduledTime == null) {
                        skippedTaskCount++
                        return@forEach
                    }

                    if (taskBackup.migrateFromChannel == DeliveryChannel.QQ.name) {
                        val sourceQqBotId = taskBackup.qqBotName?.trim()?.let { qqBotMap[it] }
                        if (sourceQqBotId == null) {
                            skippedTaskCount++
                            return@forEach
                        }
                        val migratedId = repository.migrateQqTaskToTelegram(
                            importedTask.copy(scheduledTime = scheduledTime), sourceQqBotId
                        )
                        if (migratedId != null) {
                            WorkManagerHelper.cancelTask(context, migratedId)
                            migratedTaskCount++
                            return@forEach
                        }
                    }

                    val duplicateTask = repository.findDuplicateTask(
                        deliveryChannel = channel,
                        botId = botId,
                        chatId = chatId,
                        feishuWebhookId = webhookId,
                        qqBotId = qqBotId,
                        content = taskBackup.content,
                        parseMode = importedTask.parseMode.name,
                        scheduledTime = scheduledTime,
                        isEnabled = taskBackup.isEnabled,
                        cronExpression = cron,
                        lunarMonth = lunarMonth,
                        lunarDay = lunarDay,
                        lunarLeapMonth = lunarLeapMonth
                    )
                    if (duplicateTask != null) {
                        duplicateTaskCount++
                        return@forEach
                    }

                    repository.insertTask(importedTask.copy(scheduledTime = scheduledTime))
                }

                val message = buildString {
                    append("导入成功")
                    if (migratedTaskCount > 0) append("，已迁移 $migratedTaskCount 条 QQ 任务并取消旧调度")
                    if (skippedWebhookCount > 0) append("，已跳过 $skippedWebhookCount 条无效飞书 Webhook")
                    if (skippedQqBotCount > 0) append("，已跳过 $skippedQqBotCount 条无效 QQ 机器人")
                    if (skippedUnsupportedTaskCount > 0) append("，已跳过 $skippedUnsupportedTaskCount 条不支持的任务")
                    if (skippedTaskCount > 0) append("，已跳过 $skippedTaskCount 条目标或时间无效的任务")
                    if (duplicateTaskCount > 0) append("，已跳过 $duplicateTaskCount 条重复任务")
                }
                SchedulerRecovery.recoverEnabledTasks(context, repository)
                onComplete(true, message)
            } catch (e: Exception) {
                onComplete(false, "导入失败：${e.message}")
            }
        }
    }

    fun addFeishuWebhook(
        name: String,
        webhookUrl: String,
        secret: String?,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedName = name.trim()
            if (normalizedName.isEmpty()) {
                onComplete(false, "请输入飞书 Webhook 名称")
                return@launch
            }
            val normalizedUrl = webhookUrl.trim()
            repository.validateFeishuWebhookUrl(normalizedUrl)?.let {
                onComplete(false, it)
                return@launch
            }
            if (repository.getFeishuWebhookByUrl(normalizedUrl) != null) {
                onComplete(false, "该飞书 Webhook 已存在，不能重复添加")
                return@launch
            }
            repository.insertFeishuWebhook(
                FeishuWebhook(
                    name = normalizedName,
                    webhookUrl = normalizedUrl,
                    secret = secret?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            onComplete(true, "飞书 Webhook 已添加")
        }
    }

    fun updateFeishuWebhook(
        webhook: FeishuWebhook,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedName = webhook.name.trim()
            if (normalizedName.isEmpty()) {
                onComplete(false, "请输入飞书 Webhook 名称")
                return@launch
            }
            val normalizedUrl = webhook.webhookUrl.trim()
            repository.validateFeishuWebhookUrl(normalizedUrl)?.let {
                onComplete(false, it)
                return@launch
            }
            val duplicate = repository.getFeishuWebhookByUrl(normalizedUrl)
            if (duplicate != null && duplicate.id != webhook.id) {
                onComplete(false, "该飞书 Webhook 已存在，不能重复保存")
                return@launch
            }
            repository.updateFeishuWebhook(
                webhook.copy(
                    name = normalizedName,
                    webhookUrl = normalizedUrl,
                    secret = webhook.secret?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            onComplete(true, "飞书 Webhook 已保存")
        }
    }

    fun deleteFeishuWebhook(webhook: FeishuWebhook) {
        viewModelScope.launch { repository.deleteFeishuWebhook(webhook) }
    }

    fun testFeishuWebhook(
        webhook: FeishuWebhook,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.testFeishuWebhook(webhook)
            onComplete(
                result.isSuccess,
                result.fold(
                    onSuccess = { "飞书测试消息已发送" },
                    onFailure = { "飞书测试失败：${it.message ?: "未知错误"}" }
                )
            )
        }
    }

    fun addQqBot(
        name: String,
        appId: String,
        clientSecret: String,
        groupOpenId: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val bot = QqBot(
                name = name.trim(),
                appId = appId.trim(),
                clientSecret = clientSecret.trim(),
                groupOpenId = groupOpenId.trim()
            )
            repository.validateQqBot(bot)?.let {
                onComplete(false, it)
                return@launch
            }
            if (repository.getQqBotByTarget(bot.appId, bot.groupOpenId) != null) {
                onComplete(false, "该 QQ 机器人和群目标已存在，不能重复添加")
                return@launch
            }
            repository.insertQqBot(bot)
            val migratedCount = repository.migrateFeishuTasksToSingleQqBot()
            SchedulerRecovery.recoverEnabledTasks(context, repository)
            onComplete(
                true,
                if (migratedCount > 0) {
                    "QQ 群目标已添加，已迁移 $migratedCount 条飞书任务"
                } else {
                    "QQ 群目标已添加"
                }
            )
        }
    }

    fun updateQqBot(
        bot: QqBot,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedBot = bot.copy(
                name = bot.name.trim(),
                appId = bot.appId.trim(),
                clientSecret = bot.clientSecret.trim(),
                groupOpenId = bot.groupOpenId.trim()
            )
            repository.validateQqBot(normalizedBot)?.let {
                onComplete(false, it)
                return@launch
            }
            val duplicate = repository.getQqBotByTarget(
                normalizedBot.appId,
                normalizedBot.groupOpenId
            )
            if (duplicate != null && duplicate.id != normalizedBot.id) {
                onComplete(false, "该 QQ 机器人和群目标已存在，不能重复保存")
                return@launch
            }
            repository.updateQqBot(normalizedBot)
            val migratedCount = repository.migrateFeishuTasksToSingleQqBot()
            SchedulerRecovery.recoverEnabledTasks(context, repository)
            onComplete(
                true,
                if (migratedCount > 0) {
                    "QQ 群目标已保存，已迁移 $migratedCount 条飞书任务"
                } else {
                    "QQ 群目标已保存"
                }
            )
        }
    }

    fun deleteQqBot(bot: QqBot) {
        viewModelScope.launch { repository.deleteQqBot(bot) }
    }

    fun testQqBot(bot: QqBot, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = repository.testQqBot(bot)
            onComplete(
                result.isSuccess,
                result.fold(
                    onSuccess = { "QQ 测试消息已发送" },
                    onFailure = { "QQ 测试失败：${it.message ?: "未知错误"}" }
                )
            )
        }
    }
}
