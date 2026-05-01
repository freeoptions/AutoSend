package com.autotg.data.repository

import com.autotg.data.local.AutoTGDao
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskStatus
import com.autotg.data.remote.TelegramApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramRepository @Inject constructor(
    private val telegramApi: TelegramApi,
    private val autoTGDao: AutoTGDao
) {
    suspend fun sendScheduledMessage(taskId: Long): Result<Unit> {
        val task = autoTGDao.getTaskById(taskId) ?: return Result.failure(Exception("Task not found"))
        val bot = autoTGDao.getBotById(task.botId) ?: return Result.failure(Exception("Bot not found"))
        val chat = autoTGDao.getChatById(task.chatId) ?: return Result.failure(Exception("Chat not found"))

        return try {
            val response = telegramApi.sendMessage(
                botToken = bot.token,
                chatId = chat.chatId,
                text = task.content
            )

            if (response.isSuccessful && response.body()?.ok == true) {
                autoTGDao.updateTask(task.copy(status = TaskStatus.SUCCESS, lastError = null))
                Result.success(Unit)
            } else {
                val errorMsg = response.body()?.description ?: response.message() ?: "Unknown error"
                // Don't update status to FAILED here yet if we want to retry
                autoTGDao.updateTask(task.copy(
                    lastError = errorMsg,
                    retryCount = task.retryCount + 1
                ))
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            autoTGDao.updateTask(task.copy(
                lastError = e.message,
                retryCount = task.retryCount + 1
            ))
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
    suspend fun getChatById(id: Long) = autoTGDao.getChatById(id)
    suspend fun getTaskById(id: Long) = autoTGDao.getTaskById(id)
}
