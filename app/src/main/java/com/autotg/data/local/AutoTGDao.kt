package com.autotg.data.local

import androidx.room.*
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskLog
import com.autotg.data.models.AvatarMark
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
        WHERE botId = :botId
          AND chatId = :chatId
          AND content = :content
          AND parseMode = :parseMode
          AND isEnabled = :isEnabled
          AND (
                (:cronExpression IS NULL AND cronExpression IS NULL AND scheduledTime = :scheduledTime)
                OR
                (:cronExpression IS NOT NULL AND cronExpression = :cronExpression)
              )
        LIMIT 1
        """
    )
    suspend fun findDuplicateTask(
        botId: Long,
        chatId: Long,
        content: String,
        parseMode: String,
        scheduledTime: Long,
        isEnabled: Boolean,
        cronExpression: String?
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

    // AvatarMark operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatarMark(mark: AvatarMark)

    @Delete
    suspend fun deleteAvatarMark(mark: AvatarMark)

    @Query("SELECT * FROM avatar_marks ORDER BY timestamp DESC")
    fun getAllAvatarMarks(): Flow<List<AvatarMark>>

    @Query("SELECT EXISTS(SELECT 1 FROM avatar_marks WHERE uri = :uri)")
    fun isAvatarMarked(uri: String): Flow<Boolean>

    @Query("DELETE FROM avatar_marks")
    suspend fun clearAllAvatarMarks()
}
