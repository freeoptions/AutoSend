package com.autotg.data.local

import androidx.room.*
import com.autotg.data.models.Bot
import com.autotg.data.models.Chat
import com.autotg.data.models.ScheduledTask
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

    // ScheduledTask operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ScheduledTask): Long

    @Update
    suspend fun updateTask(task: ScheduledTask)

    @Delete
    suspend fun deleteTask(task: ScheduledTask)

    @Query("SELECT * FROM scheduled_tasks")
    fun getAllTasks(): Flow<List<ScheduledTask>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): ScheduledTask?

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'PENDING'")
    fun getPendingTasks(): Flow<List<ScheduledTask>>
}
