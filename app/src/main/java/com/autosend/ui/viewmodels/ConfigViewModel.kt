package com.autosend.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autosend.data.models.BackupData
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
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
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
            val tasks = repository.getAllTasks().first()
                .filter {
                    it.deliveryChannel == DeliveryChannel.FEISHU ||
                        it.deliveryChannel == DeliveryChannel.QQ
                }
            val backup = BackupData(
                bots = emptyList(),
                chats = emptyList(),
                feishuWebhooks = webhooks.map {
                    FeishuWebhookBackup(it.name, it.webhookUrl, it.secret)
                },
                qqBots = qqBots.map {
                    QqBotBackup(it.name, it.appId, it.clientSecret, it.groupOpenId)
                },
                tasks = tasks.map { task ->
                    TaskBackup(
                        botName = "",
                        chatName = "",
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
                        parseMode = MessageParseMode.NONE.name,
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

                val now = System.currentTimeMillis()
                var skippedUnsupportedTaskCount = 0
                var skippedTaskCount = 0
                var duplicateTaskCount = 0
                backup.tasks.forEach { taskBackup ->
                    val channel = when (taskBackup.deliveryChannel?.uppercase(Locale.ROOT)) {
                        DeliveryChannel.FEISHU.name -> DeliveryChannel.FEISHU
                        DeliveryChannel.QQ.name -> DeliveryChannel.QQ
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
                    if ((channel == DeliveryChannel.FEISHU && webhookId == null) ||
                        (channel == DeliveryChannel.QQ && qqBotId == null)
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
                        botId = null,
                        chatId = null,
                        feishuWebhookId = webhookId,
                        qqBotId = qqBotId,
                        content = taskBackup.content,
                        parseMode = MessageParseMode.NONE,
                        scheduledTime = timeSeed,
                        isEnabled = taskBackup.isEnabled,
                        cronExpression = cron,
                        lunarMonth = lunarMonth,
                        lunarDay = lunarDay,
                        lunarLeapMonth = lunarLeapMonth
                    )
                    val scheduledTime = when {
                        RecurringScheduleUtils.isRecurring(importedTask) &&
                            taskBackup.scheduledTime <= now ->
                            RecurringScheduleUtils.getNextExecutionTimeFrom(importedTask, now + 1_000L)
                        taskBackup.scheduledTime > 0L -> taskBackup.scheduledTime
                        else -> null
                    }
                    if (scheduledTime == null) {
                        skippedTaskCount++
                        return@forEach
                    }

                    val duplicateTask = repository.findDuplicateTask(
                        deliveryChannel = channel,
                        botId = null,
                        chatId = null,
                        feishuWebhookId = webhookId,
                        qqBotId = qqBotId,
                        content = taskBackup.content,
                        parseMode = MessageParseMode.NONE.name,
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

                val migratedCount = repository.migrateFeishuTasksToSingleQqBot()
                val message = buildString {
                    append("导入成功")
                    if (skippedWebhookCount > 0) append("，已跳过 $skippedWebhookCount 条无效飞书 Webhook")
                    if (skippedQqBotCount > 0) append("，已跳过 $skippedQqBotCount 条无效 QQ 机器人")
                    if (skippedUnsupportedTaskCount > 0) append("，已跳过 $skippedUnsupportedTaskCount 条不支持的任务")
                    if (skippedTaskCount > 0) append("，已跳过 $skippedTaskCount 条目标或时间无效的任务")
                    if (duplicateTaskCount > 0) append("，已跳过 $duplicateTaskCount 条重复任务")
                    if (migratedCount > 0) append("，已将 $migratedCount 条飞书任务迁移到 QQ")
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
