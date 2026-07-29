package com.autotg.data.repository

import com.autotg.data.local.AutoTGDao
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskLog
import com.autotg.data.models.AvatarMark
import com.autotg.data.models.TaskStatus
import com.autotg.data.remote.TelegramApi
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramRepository @Inject constructor(
    private val telegramApi: TelegramApi,
    private val autoTGDao: AutoTGDao
) {
    suspend fun sendScheduledMessage(taskId: Long, isManual: Boolean = false): Result<Unit> {
        val task = autoTGDao.getTaskById(taskId) ?: return Result.failure(Exception("Task not found"))
        val bot = autoTGDao.getBotById(task.botId) ?: return Result.failure(Exception("Bot not found"))
        val chat = autoTGDao.getChatById(task.chatId) ?: return Result.failure(Exception("Chat not found"))

        return try {
            val response = telegramApi.sendMessage(
                botToken = bot.token,
                chatId = chat.chatId,
                text = task.content,
                parseMode = task.parseMode.toTelegramValue()
            )

            if (response.isSuccessful && response.body()?.ok == true) {
                // Only update task status if it's NOT a manual send
                if (!isManual) {
                    autoTGDao.updateTask(task.copy(
                        status = TaskStatus.SUCCESS,
                        retryCount = 0,
                        lastError = null
                    ))
                }
                Result.success(Unit)
            } else {
                val errorMsg = response.body()?.description ?: response.message() ?: "Unknown error"
                if (!isManual) {
                    autoTGDao.updateTask(task.copy(
                        lastError = errorMsg,
                        retryCount = task.retryCount + 1
                    ))
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            if (!isManual) {
                autoTGDao.updateTask(task.copy(
                    lastError = e.message,
                    retryCount = task.retryCount + 1
                ))
            }
            Result.failure(e)
        }
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
        botId: Long,
        chatId: Long,
        content: String,
        parseMode: String,
        scheduledTime: Long,
        isEnabled: Boolean,
        cronExpression: String?
    ) = autoTGDao.findDuplicateTask(
        botId = botId,
        chatId = chatId,
        content = content,
        parseMode = parseMode,
        scheduledTime = scheduledTime,
        isEnabled = isEnabled,
        cronExpression = cronExpression
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

    // AvatarMark operations
    fun getAllAvatarMarks() = autoTGDao.getAllAvatarMarks()
    fun isAvatarMarked(uri: String) = autoTGDao.isAvatarMarked(uri)
    suspend fun insertAvatarMark(mark: AvatarMark) = autoTGDao.insertAvatarMark(mark)
    suspend fun deleteAvatarMark(mark: AvatarMark) = autoTGDao.deleteAvatarMark(mark)
    suspend fun clearAllAvatarMarks() = autoTGDao.clearAllAvatarMarks()
    suspend fun isAvatarMarkedOnce(uri: String) = autoTGDao.isAvatarMarked(uri).first()
}
