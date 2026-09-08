package com.autotg.data.local

import androidx.room.*
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskLog
import com.autotg.data.models.FeishuWebhook
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoTGDao {
    // Bot operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBot(bot: Bot): Long

    @Update
    suspend fun updateBot(bot: Bot)

    @Delete
    suspend fun deleteBot(bot: Bot)

    @Query("SELECT * FROM bots")
    fun getAllBots(): Flow<List<Bot>>

    @Query("SELECT * FROM bots WHERE id = :id")
    suspend fun getBotById(id: Long): Bot?

    @Query("SELECT * FROM bots WHERE token = :token LIMIT 1")
    suspend fun getBotByToken(token: String): Bot?

    // Chat operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: Chat): Long

    @Update
    suspend fun updateChat(chat: Chat)

    @Delete
    suspend fun deleteChat(chat: Chat)

    @Query("SELECT * FROM chats")
    fun getAllChats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChatById(id: Long): Chat?

    @Query("SELECT * FROM chats WHERE chatId = :chatId LIMIT 1")
    suspend fun getChatByChatId(chatId: String): Chat?

    // Feishu webhook operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeishuWebhook(webhook: FeishuWebhook): Long

    @Update
    suspend fun updateFeishuWebhook(webhook: FeishuWebhook)

    @Delete
    suspend fun deleteFeishuWebhook(webhook: FeishuWebhook)

    @Query("SELECT * FROM feishu_webhooks ORDER BY id ASC")
    fun getAllFeishuWebhooks(): Flow<List<FeishuWebhook>>

    @Query("SELECT * FROM feishu_webhooks WHERE id = :id")
    suspend fun getFeishuWebhookById(id: Long): FeishuWebhook?

    @Query("SELECT * FROM feishu_webhooks WHERE webhookUrl = :webhookUrl LIMIT 1")
    suspend fun getFeishuWebhookByUrl(webhookUrl: String): FeishuWebhook?

    // ScheduledTask operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ScheduledTask): Long

    @Update
    suspend fun updateTask(task: ScheduledTask)

    @Delete
    suspend fun deleteTask(task: ScheduledTask)

    @Query("SELECT * FROM scheduled_tasks ORDER BY scheduledTime ASC, id ASC")
    fun getAllTasks(): Flow<List<ScheduledTask>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): ScheduledTask?

    @Query(
        """
        SELECT * FROM scheduled_tasks
        WHERE ((:botId IS NULL AND botId IS NULL) OR botId = :botId)
          AND ((:chatId IS NULL AND chatId IS NULL) OR chatId = :chatId)
          AND deliveryChannel = :deliveryChannel
          AND ((:feishuWebhookId IS NULL AND feishuWebhookId IS NULL) OR feishuWebhookId = :feishuWebhookId)
          AND content = :content
          AND parseMode = :parseMode
          AND isEnabled = :isEnabled
          AND (
                (:lunarMonth IS NULL AND lunarMonth IS NULL AND lunarDay IS NULL AND lunarLeapMonth = 0)
                OR
                (
                    :lunarMonth IS NOT NULL
                    AND lunarMonth = :lunarMonth
                    AND lunarDay = :lunarDay
                    AND lunarLeapMonth = :lunarLeapMonth
                )
              )
          AND (
                (
                    :cronExpression IS NULL
                    AND :lunarMonth IS NULL
                    AND cronExpression IS NULL
                    AND scheduledTime = :scheduledTime
                )
                OR
                (:cronExpression IS NOT NULL AND cronExpression = :cronExpression)
                OR
                (
                    :lunarMonth IS NOT NULL
                    AND lunarMonth = :lunarMonth
                    AND lunarDay = :lunarDay
                    AND lunarLeapMonth = :lunarLeapMonth
                )
              )
        LIMIT 1
        """
    )
    suspend fun findDuplicateTask(
        deliveryChannel: String,
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
    ): ScheduledTask?

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'PENDING' ORDER BY scheduledTime ASC, id ASC")
    fun getPendingTasks(): Flow<List<ScheduledTask>>

    // TaskLog operations
    @Insert
    suspend fun insertLog(log: TaskLog): Long

    @Update
    suspend fun updateLog(log: TaskLog)

    @Query("SELECT * FROM task_logs ORDER BY timestamp DESC LIMIT 500")
    fun getAllLogs(): Flow<List<TaskLog>>

    @Query("SELECT * FROM task_logs WHERE id = :id")
    suspend fun getLogById(id: Long): TaskLog?

    @Query("DELETE FROM task_logs")
    suspend fun clearAllLogs()

}
