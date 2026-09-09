package com.autosend.data.repository

import com.autosend.data.local.AutoSendDao
import com.autosend.data.models.Bot
import com.autosend.data.models.Chat
import com.autosend.data.models.ScheduledTask
import com.autosend.data.models.TaskLog
import com.autosend.data.models.TaskStatus
import com.autosend.data.models.DeliveryChannel
import com.autosend.data.models.FeishuWebhook
import com.autosend.data.remote.FeishuApi
import com.autosend.data.remote.FeishuMessageRequest
import com.autosend.data.remote.FeishuTextContent
import com.autosend.utils.FeishuSigner
import kotlinx.coroutines.flow.first
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramRepository @Inject constructor(
    private val feishuApi: FeishuApi,
    private val autoSendDao: AutoSendDao
) {
    suspend fun sendScheduledMessage(
        taskId: Long,
        isManual: Boolean = false,
        updateTaskState: Boolean = !isManual
    ): Result<Unit> {
        val task = autoSendDao.getTaskById(taskId) ?: return Result.failure(Exception("Task not found"))
        return try {
            val result = when (task.deliveryChannel) {
                DeliveryChannel.TELEGRAM -> Result.failure(Exception("Telegram 任务已停用，请改用飞书任务"))
                DeliveryChannel.FEISHU -> sendFeishuMessage(task)
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

}

data class TaskTargetDescription(
    val channelName: String,
    val targetName: String
)
