package com.autotg.data.repository

import com.autotg.data.local.AutoTGDao
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskLog
import com.autotg.data.models.TaskStatus
import com.autotg.data.models.DeliveryChannel
import com.autotg.data.models.FeishuWebhook
import com.autotg.data.remote.FeishuApi
import com.autotg.data.remote.FeishuMessageRequest
import com.autotg.data.remote.FeishuTextContent
import com.autotg.utils.FeishuSigner
import kotlinx.coroutines.flow.first
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramRepository @Inject constructor(
    private val feishuApi: FeishuApi,
    private val autoTGDao: AutoTGDao
) {
    suspend fun sendScheduledMessage(
        taskId: Long,
        isManual: Boolean = false,
        updateTaskState: Boolean = !isManual
    ): Result<Unit> {
        val task = autoTGDao.getTaskById(taskId) ?: return Result.failure(Exception("Task not found"))
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
                autoTGDao.updateTask(task.copy(
                    lastError = detailedError,
                    retryCount = task.retryCount + 1
                ))
            }
            Result.failure(Exception(detailedError, e))
        }
    }

    private suspend fun sendFeishuMessage(task: ScheduledTask): Result<Unit> {
        val webhookId = task.feishuWebhookId ?: return Result.failure(Exception("飞书任务缺少 Webhook"))
        val webhook = autoTGDao.getFeishuWebhookById(webhookId)
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
            autoTGDao.updateTask(
                task.copy(status = TaskStatus.SUCCESS, retryCount = 0, lastError = null)
            )
        } else {
            autoTGDao.updateTask(
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
                val bot = task.botId?.let { autoTGDao.getBotById(it) }
                val chat = task.chatId?.let { autoTGDao.getChatById(it) }
                TaskTargetDescription(
                    channelName = "Telegram",
                    targetName = listOfNotNull(bot?.name, chat?.name).joinToString(" / ")
                        .ifBlank { "未知 Telegram 目标" }
                )
            }

            DeliveryChannel.FEISHU -> {
                val webhook = task.feishuWebhookId?.let { autoTGDao.getFeishuWebhookById(it) }
                TaskTargetDescription("飞书", webhook?.name ?: "未知飞书群")
            }
        }
    }

    suspend fun testFeishuWebhook(webhook: FeishuWebhook): Result<Unit> {
        val temporaryTask = ScheduledTask(
            deliveryChannel = DeliveryChannel.FEISHU,
            feishuWebhookId = webhook.id,
            content = "AutoTG 飞书连接测试成功",
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
    fun getAllTasks() = autoTGDao.getAllTasks()
    fun getPendingTasks() = autoTGDao.getPendingTasks()
    suspend fun insertTask(task: ScheduledTask) = autoTGDao.insertTask(task)
    suspend fun updateTask(task: ScheduledTask) = autoTGDao.updateTask(task)
    suspend fun deleteTask(task: ScheduledTask) = autoTGDao.deleteTask(task)

    suspend fun getBotById(id: Long) = autoTGDao.getBotById(id)
    suspend fun getBotByToken(token: String) = autoTGDao.getBotByToken(token)
    suspend fun getChatById(id: Long) = autoTGDao.getChatById(id)
    suspend fun getChatByChatId(chatId: String) = autoTGDao.getChatByChatId(chatId)
    suspend fun getTaskById(id: Long) = autoTGDao.getTaskById(id)
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
    ) = autoTGDao.findDuplicateTask(
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
    fun getAllLogs() = autoTGDao.getAllLogs()
    suspend fun insertLog(log: TaskLog) = autoTGDao.insertLog(log)
    suspend fun updateLog(log: TaskLog) = autoTGDao.updateLog(log)
    suspend fun getLogById(id: Long) = autoTGDao.getLogById(id)
    suspend fun clearAllLogs() = autoTGDao.clearAllLogs()

    // Bot operations
    fun getAllBots() = autoTGDao.getAllBots()
    suspend fun insertBot(bot: Bot) = autoTGDao.insertBot(bot)
    suspend fun updateBot(bot: Bot) = autoTGDao.updateBot(bot)
    suspend fun deleteBot(bot: Bot) = autoTGDao.deleteBot(bot)

    // Chat operations
    fun getAllChats() = autoTGDao.getAllChats()
    suspend fun insertChat(chat: Chat) = autoTGDao.insertChat(chat)
    suspend fun updateChat(chat: Chat) = autoTGDao.updateChat(chat)
    suspend fun deleteChat(chat: Chat) = autoTGDao.deleteChat(chat)

    // Feishu webhook operations
    fun getAllFeishuWebhooks() = autoTGDao.getAllFeishuWebhooks()
    suspend fun getFeishuWebhookById(id: Long) = autoTGDao.getFeishuWebhookById(id)
    suspend fun getFeishuWebhookByUrl(url: String) = autoTGDao.getFeishuWebhookByUrl(url)
    suspend fun insertFeishuWebhook(webhook: FeishuWebhook) = autoTGDao.insertFeishuWebhook(webhook)
    suspend fun updateFeishuWebhook(webhook: FeishuWebhook) = autoTGDao.updateFeishuWebhook(webhook)
    suspend fun deleteFeishuWebhook(webhook: FeishuWebhook) = autoTGDao.deleteFeishuWebhook(webhook)

}

data class TaskTargetDescription(
    val channelName: String,
    val targetName: String
)
