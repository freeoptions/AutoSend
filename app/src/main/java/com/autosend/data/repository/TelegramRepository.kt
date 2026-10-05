package com.autosend.data.repository

import com.autosend.data.local.AutoSendDao
import com.autosend.data.models.Bot
import com.autosend.data.models.Chat
import com.autosend.data.models.ScheduledTask
import com.autosend.data.models.TaskLog
import com.autosend.data.models.TaskStatus
import com.autosend.data.models.DeliveryChannel
import com.autosend.data.models.FeishuWebhook
import com.autosend.data.models.QqBot
import com.autosend.data.remote.QqAccessTokenRequest
import com.autosend.data.remote.QqApi
import com.autosend.data.remote.QqMessageRequest
import com.autosend.data.remote.FeishuApi
import com.autosend.data.remote.FeishuMessageRequest
import com.autosend.data.remote.FeishuTextContent
import com.autosend.utils.FeishuSigner
import com.autosend.utils.DeliveryChannelPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramRepository @Inject constructor(
    private val feishuApi: FeishuApi,
    private val qqApi: QqApi,
    private val autoSendDao: AutoSendDao
) {
    private val qqTokenMutex = Mutex()
    private val qqTokenCache = ConcurrentHashMap<Long, CachedQqToken>()

    suspend fun sendScheduledMessage(
        taskId: Long,
        isManual: Boolean = false,
        updateTaskState: Boolean = !isManual
    ): Result<Unit> {
        val task = autoSendDao.getTaskById(taskId) ?: return Result.failure(Exception("Task not found"))
        if (!DeliveryChannelPolicy.isEnabled(task.deliveryChannel)) {
            return Result.failure(Exception("该发送通道已停用"))
        }
        return try {
            val result = when (task.deliveryChannel) {
                DeliveryChannel.TELEGRAM -> Result.failure(Exception("Telegram 任务已停用，请改用 QQ 或飞书任务"))
                DeliveryChannel.FEISHU -> sendFeishuMessage(task)
                DeliveryChannel.QQ -> sendQqMessage(task)
            }
            updateTaskAfterSend(task, result, updateTaskState)
            result
        } catch (e: Exception) {
            val detailedError = describeException(e)
            if (updateTaskState) {
                autoSendDao.updateTask(task.copy(
                    lastError = detailedError,
                    retryCount = task.retryCount + 1
                ))
            }
            Result.failure(Exception(detailedError, e))
        }
    }

    private suspend fun sendFeishuMessage(task: ScheduledTask): Result<Unit> {
        val webhookId = task.feishuWebhookId ?: return Result.failure(Exception("飞书任务缺少 Webhook"))
        val webhook = autoSendDao.getFeishuWebhookById(webhookId)
            ?: return Result.failure(Exception("飞书 Webhook 不存在或已删除"))
        validateFeishuWebhookUrl(webhook.webhookUrl)?.let {
            return Result.failure(Exception(it))
        }

        val timestamp = System.currentTimeMillis() / 1_000L
        val secret = webhook.secret?.trim().orEmpty()
        val response = feishuApi.sendMessage(
            webhookUrl = webhook.webhookUrl.trim(),
            request = FeishuMessageRequest(
                timestamp = if (secret.isNotEmpty()) timestamp.toString() else null,
                sign = if (secret.isNotEmpty()) FeishuSigner.sign(timestamp, secret) else null,
                content = FeishuTextContent(task.content)
            )
        )
        val body = response.body()
        if (response.isSuccessful && body?.isSuccessful() == true) {
            return Result.success(Unit)
        }
        val error = body?.errorMessage()
            ?: response.errorBody()?.string()?.take(300)
            ?: response.message().takeIf { it.isNotBlank() }
            ?: "飞书返回未知错误"
        return Result.failure(Exception("飞书 Webhook ${response.code()}: $error"))
    }

    private suspend fun sendQqMessage(task: ScheduledTask): Result<Unit> {
        val botId = task.qqBotId ?: return Result.failure(Exception("QQ 任务缺少机器人配置"))
        val bot = autoSendDao.getQqBotById(botId)
            ?: return Result.failure(Exception("QQ 机器人配置不存在或已删除"))
        validateQqBot(bot)?.let { return Result.failure(Exception(it)) }

        val accessToken = getQqAccessToken(bot)
        val response = qqApi.sendGroupMessage(
            authorization = "QQBot $accessToken",
            groupOpenId = bot.groupOpenId.trim(),
            request = QqMessageRequest(content = task.content)
        )
        val body = response.body()
        if (response.isSuccessful && body?.isSuccessful() == true) {
            return Result.success(Unit)
        }
        val error = body?.errorMessage()
            ?: response.errorBody()?.string()?.take(300)
            ?: response.message().takeIf { it.isNotBlank() }
            ?: "QQ 返回未知错误"
        val code = body?.errorCode?.let { " $it" }.orEmpty()
        return Result.failure(Exception("QQ 群消息${code}：$error"))
    }

    private suspend fun getQqAccessToken(bot: QqBot): String {
        val now = System.currentTimeMillis()
        qqTokenCache[bot.id]?.takeIf { it.expiresAtMillis > now + TOKEN_REFRESH_GUARD_MS }?.let {
            return it.accessToken
        }

        return qqTokenMutex.withLock {
            val refreshedNow = System.currentTimeMillis()
            qqTokenCache[bot.id]
                ?.takeIf { it.expiresAtMillis > refreshedNow + TOKEN_REFRESH_GUARD_MS }
                ?.let { return@withLock it.accessToken }

            val response = qqApi.getAccessToken(
                QqAccessTokenRequest(
                    appId = bot.appId.trim(),
                    clientSecret = bot.clientSecret.trim()
                )
            )
            val body = response.body()
            if (!response.isSuccessful || body == null || !body.isSuccessful()) {
                val error = body?.message?.takeIf { it.isNotBlank() }
                    ?: response.errorBody()?.string()?.take(300)
                    ?: response.message().takeIf { it.isNotBlank() }
                    ?: "QQ Access Token 获取失败"
                val code = body?.code?.let { " $it" }.orEmpty()
                throw Exception("QQ Access Token${code}：$error")
            }

            val accessToken = body.accessToken
                ?: throw Exception("QQ Access Token 为空")
            val expiresInMillis = (body.expiresIn?.toLongOrNull() ?: DEFAULT_TOKEN_LIFETIME_SECONDS) * 1_000L
            qqTokenCache[bot.id] = CachedQqToken(
                accessToken = accessToken,
                expiresAtMillis = System.currentTimeMillis() + expiresInMillis
            )
            accessToken
        }
    }

    private suspend fun updateTaskAfterSend(
        task: ScheduledTask,
        result: Result<Unit>,
        updateTaskState: Boolean
    ) {
        if (!updateTaskState) return
        if (result.isSuccess) {
            autoSendDao.updateTask(
                task.copy(status = TaskStatus.SUCCESS, retryCount = 0, lastError = null)
            )
        } else {
            autoSendDao.updateTask(
                task.copy(
                    lastError = result.exceptionOrNull()?.message,
                    retryCount = task.retryCount + 1
                )
            )
        }
    }

    suspend fun describeTaskTarget(task: ScheduledTask): TaskTargetDescription {
        return when (task.deliveryChannel) {
            DeliveryChannel.TELEGRAM -> {
                val bot = task.botId?.let { autoSendDao.getBotById(it) }
                val chat = task.chatId?.let { autoSendDao.getChatById(it) }
                TaskTargetDescription(
                    channelName = "Telegram",
                    targetName = listOfNotNull(bot?.name, chat?.name).joinToString(" / ")
                        .ifBlank { "未知 Telegram 目标" }
                )
            }

            DeliveryChannel.FEISHU -> {
                val webhook = task.feishuWebhookId?.let { autoSendDao.getFeishuWebhookById(it) }
                TaskTargetDescription("飞书", webhook?.name ?: "未知飞书群")
            }

            DeliveryChannel.QQ -> {
                val bot = task.qqBotId?.let { autoSendDao.getQqBotById(it) }
                TaskTargetDescription("QQ 群", bot?.name ?: "未知 QQ 群")
            }
        }
    }

    suspend fun testFeishuWebhook(webhook: FeishuWebhook): Result<Unit> {
        val temporaryTask = ScheduledTask(
            deliveryChannel = DeliveryChannel.FEISHU,
            feishuWebhookId = webhook.id,
            content = "AutoSend 飞书连接测试成功",
            scheduledTime = System.currentTimeMillis()
        )
        return if (webhook.id > 0L) {
            runCatching { sendFeishuMessage(temporaryTask).getOrThrow() }
        } else {
            Result.failure(Exception("请先保存飞书 Webhook，再执行测试"))
        }
    }

    suspend fun testQqBot(bot: QqBot): Result<Unit> {
        val temporaryTask = ScheduledTask(
            deliveryChannel = DeliveryChannel.QQ,
            qqBotId = bot.id,
            content = "AutoSend QQ 连接测试成功",
            scheduledTime = System.currentTimeMillis()
        )
        return if (bot.id > 0L) {
            runCatching { sendQqMessage(temporaryTask).getOrThrow() }
        } else {
            Result.failure(Exception("请先保存 QQ 机器人，再执行测试"))
        }
    }

    fun validateFeishuWebhookUrl(webhookUrl: String): String? {
        return runCatching {
            val uri = URI(webhookUrl.trim())
            when {
                uri.scheme != "https" -> "飞书 Webhook 必须使用 HTTPS"
                uri.host != "open.feishu.cn" -> "Webhook 域名必须是 open.feishu.cn"
                uri.port != -1 || uri.userInfo != null -> "飞书 Webhook 地址不允许自定义端口或用户信息"
                uri.query != null || uri.fragment != null -> "飞书 Webhook 地址不应包含查询参数或片段"
                !uri.path.startsWith("/open-apis/bot/v2/hook/") -> "飞书 Webhook 路径格式不正确"
                uri.path.substringAfterLast('/').isBlank() -> "飞书 Webhook 缺少机器人标识"
                else -> null
            }
        }.getOrElse { "飞书 Webhook 地址格式不正确" }
    }

    fun validateQqBot(bot: QqBot): String? {
        return when {
            bot.name.trim().isEmpty() -> "请输入 QQ 目标名称"
            bot.appId.trim().isEmpty() -> "请输入 QQ AppID"
            bot.clientSecret.trim().isEmpty() -> "请输入 QQ AppSecret"
            bot.groupOpenId.trim().isEmpty() -> "请输入 QQ 群 OpenID"
            else -> null
        }
    }

    private fun describeException(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val type = root::class.java.simpleName.takeIf { it.isNotBlank() } ?: "网络异常"
        val message = root.message?.takeIf { it.isNotBlank() }
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: "未知错误"
        return "$type: $message"
    }

    // Proxy methods for Dao
    fun getAllTasks() = autoSendDao.getAllTasks()
    fun getPendingTasks() = autoSendDao.getPendingTasks()
    suspend fun insertTask(task: ScheduledTask) = autoSendDao.insertTask(task)
    suspend fun updateTask(task: ScheduledTask) = autoSendDao.updateTask(task)
    suspend fun deleteTask(task: ScheduledTask) = autoSendDao.deleteTask(task)

    /**
     * 将历史飞书任务原地改绑到唯一的 QQ 群目标，保留任务 ID、内容、时间和重复规则。
     * 只有检测到唯一 QQ 目标时才执行，避免多个 QQ 目标时误绑到错误的群。
     */
    suspend fun migrateFeishuTasksToSingleQqBot(): Int {
        val qqBots = autoSendDao.getAllQqBots().first()
        if (qqBots.size != 1) return 0

        val qqBotId = qqBots.single().id
        val feishuTasks = autoSendDao.getAllTasks().first()
            .filter { it.deliveryChannel == DeliveryChannel.FEISHU }
        feishuTasks.forEach { task ->
            autoSendDao.updateTask(
                task.copy(
                    deliveryChannel = DeliveryChannel.QQ,
                    botId = null,
                    chatId = null,
                    feishuWebhookId = null,
                    qqBotId = qqBotId
                )
            )
        }
        return feishuTasks.size
    }

    suspend fun getBotById(id: Long) = autoSendDao.getBotById(id)
    suspend fun getBotByToken(token: String) = autoSendDao.getBotByToken(token)
    suspend fun getChatById(id: Long) = autoSendDao.getChatById(id)
    suspend fun getChatByChatId(chatId: String) = autoSendDao.getChatByChatId(chatId)
    suspend fun getTaskById(id: Long) = autoSendDao.getTaskById(id)
    suspend fun findDuplicateTask(
        deliveryChannel: DeliveryChannel,
        botId: Long?,
        chatId: Long?,
        feishuWebhookId: Long?,
        qqBotId: Long?,
        content: String,
        parseMode: String,
        scheduledTime: Long,
        isEnabled: Boolean,
        cronExpression: String?,
        lunarMonth: Int?,
        lunarDay: Int?,
        lunarLeapMonth: Boolean
    ) = autoSendDao.findDuplicateTask(
        deliveryChannel = deliveryChannel.name,
        botId = botId,
        chatId = chatId,
        feishuWebhookId = feishuWebhookId,
        qqBotId = qqBotId,
        content = content,
        parseMode = parseMode,
        scheduledTime = scheduledTime,
        isEnabled = isEnabled,
        cronExpression = cronExpression,
        lunarMonth = lunarMonth,
        lunarDay = lunarDay,
        lunarLeapMonth = lunarLeapMonth
    )

    // Log operations
    fun getAllLogs() = autoSendDao.getAllLogs()
    suspend fun insertLog(log: TaskLog) = autoSendDao.insertLog(log)
    suspend fun updateLog(log: TaskLog) = autoSendDao.updateLog(log)
    suspend fun getLogById(id: Long) = autoSendDao.getLogById(id)
    suspend fun markAllVisibleLogsAsRead() = autoSendDao.markAllVisibleLogsAsRead()
    suspend fun clearVisibleLogs() = autoSendDao.clearVisibleLogs()

    // Bot operations
    fun getAllBots() = autoSendDao.getAllBots()
    suspend fun insertBot(bot: Bot) = autoSendDao.insertBot(bot)
    suspend fun updateBot(bot: Bot) = autoSendDao.updateBot(bot)
    suspend fun deleteBot(bot: Bot) = autoSendDao.deleteBot(bot)

    // Chat operations
    fun getAllChats() = autoSendDao.getAllChats()
    suspend fun insertChat(chat: Chat) = autoSendDao.insertChat(chat)
    suspend fun updateChat(chat: Chat) = autoSendDao.updateChat(chat)
    suspend fun deleteChat(chat: Chat) = autoSendDao.deleteChat(chat)

    // Feishu webhook operations
    fun getAllFeishuWebhooks() = autoSendDao.getAllFeishuWebhooks()
    suspend fun getFeishuWebhookById(id: Long) = autoSendDao.getFeishuWebhookById(id)
    suspend fun getFeishuWebhookByUrl(url: String) = autoSendDao.getFeishuWebhookByUrl(url)
    suspend fun insertFeishuWebhook(webhook: FeishuWebhook) = autoSendDao.insertFeishuWebhook(webhook)
    suspend fun updateFeishuWebhook(webhook: FeishuWebhook) = autoSendDao.updateFeishuWebhook(webhook)
    suspend fun deleteFeishuWebhook(webhook: FeishuWebhook) = autoSendDao.deleteFeishuWebhook(webhook)

    // QQ official bot operations
    fun getAllQqBots() = autoSendDao.getAllQqBots()
    suspend fun getQqBotById(id: Long) = autoSendDao.getQqBotById(id)
    suspend fun getQqBotByTarget(appId: String, groupOpenId: String) =
        autoSendDao.getQqBotByTarget(appId, groupOpenId)
    suspend fun insertQqBot(bot: QqBot) = autoSendDao.insertQqBot(bot)
    suspend fun updateQqBot(bot: QqBot) {
        qqTokenCache.remove(bot.id)
        autoSendDao.updateQqBot(bot)
    }
    suspend fun deleteQqBot(bot: QqBot) {
        qqTokenCache.remove(bot.id)
        autoSendDao.deleteQqBot(bot)
    }

}

private data class CachedQqToken(
    val accessToken: String,
    val expiresAtMillis: Long
)

private const val TOKEN_REFRESH_GUARD_MS = 60_000L
private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 7_200L

data class TaskTargetDescription(
    val channelName: String,
    val targetName: String
)
