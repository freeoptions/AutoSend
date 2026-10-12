package com.autosend.data.local

import androidx.room.*
import com.autosend.data.models.Bot
import com.autosend.data.models.Chat
import com.autosend.data.models.ScheduledTask
import com.autosend.data.models.TaskLog
import com.autosend.data.models.FeishuWebhook
import com.autosend.data.models.QqBot
import com.autosend.data.models.PendingMessage
import com.autosend.data.models.DeliveryGate
import com.autosend.data.models.ReadyMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoSendDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingMessage(message: PendingMessage): Long

    @Update
    suspend fun updatePendingMessage(message: PendingMessage)

    @Query("SELECT * FROM pending_messages WHERE id = :id")
    suspend fun getPendingMessage(id: Long): PendingMessage?

    @Query("SELECT * FROM pending_messages WHERE state NOT IN ('SENT', 'CANCELLED') ORDER BY occurrenceAt, id LIMIT 200")
    fun observePendingMessages(): Flow<List<PendingMessage>>

    @Query("SELECT COUNT(*) FROM pending_messages WHERE state NOT IN ('SENT', 'CANCELLED')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM delivery_gates")
    fun observeDeliveryGates(): Flow<List<DeliveryGate>>

    @Query("SELECT * FROM delivery_gates WHERE scope = :scope")
    suspend fun getDeliveryGate(scope: String): DeliveryGate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDeliveryGate(gate: DeliveryGate)

    @Query("DELETE FROM delivery_gates WHERE (untilMillis = 9223372036854775807 AND scope != 'pause') OR scope = 'network'")
    suspend fun resetBlockedGates()

    @Query("DELETE FROM delivery_gates WHERE (scope = 'bot:' || :botId OR scope = 'chat:' || :botId || ':' || :chatId) AND untilMillis = 9223372036854775807")
    suspend fun resetTargetGates(botId: Long, chatId: Long)

    @Query("UPDATE pending_messages SET content = :content, state = 'WAITING', nextAttemptAt = 0, lastError = NULL WHERE id = :id AND state IN ('WAITING', 'BLOCKED') AND EXISTS (SELECT 1 FROM scheduled_tasks t WHERE t.id = pending_messages.taskId)")
    suspend fun editPendingMessage(id: Long, content: String)

    @Query("UPDATE pending_messages SET state = 'WAITING', nextAttemptAt = 0 WHERE state = 'BLOCKED' AND EXISTS (SELECT 1 FROM scheduled_tasks t WHERE t.id = pending_messages.taskId)")
    suspend fun retryBlockedMessages()

    @Query("UPDATE pending_messages SET state = 'CANCELLED', leaseUntil = 0 WHERE id = :id AND state != 'SENT'")
    suspend fun cancelPendingMessage(id: Long)

    @Query("UPDATE pending_messages SET state = 'BLOCKED', lastError = '原任务已删除，请确认后取消该消息' WHERE taskId = :taskId AND state NOT IN ('SENT', 'CANCELLED')")
    suspend fun preserveMessagesForDeletedTask(taskId: Long)

    @Query("""
        SELECT p.*, MAX(p.nextAttemptAt, p.leaseUntil,
            COALESCE(n.untilMillis, 0), COALESCE(b.untilMillis, 0),
            COALESCE(c.untilMillis, 0), COALESCE(s.untilMillis, 0)) AS readyAt
        FROM pending_messages p
        LEFT JOIN delivery_gates n ON n.scope = 'network'
        LEFT JOIN delivery_gates b ON b.scope = 'bot:' || p.botId
        LEFT JOIN delivery_gates c ON c.scope = 'chat:' || p.botId || ':' || p.chatId
        LEFT JOIN delivery_gates s ON s.scope = 'pause'
        WHERE p.state IN ('WAITING', 'SENDING')
          AND NOT EXISTS (SELECT 1 FROM scheduled_tasks t WHERE t.id = p.taskId AND t.isEnabled = 0 AND p.manual = 0)
          AND COALESCE(b.untilMillis, 0) != 9223372036854775807
          AND COALESCE(c.untilMillis, 0) != 9223372036854775807
          AND COALESCE(s.untilMillis, 0) != 9223372036854775807
        ORDER BY CASE WHEN MAX(p.nextAttemptAt, p.leaseUntil, COALESCE(n.untilMillis, 0),
            COALESCE(b.untilMillis, 0), COALESCE(c.untilMillis, 0), COALESCE(s.untilMillis, 0)) <= :now THEN 0
            ELSE MAX(p.nextAttemptAt, p.leaseUntil, COALESCE(n.untilMillis, 0),
            COALESCE(b.untilMillis, 0), COALESCE(c.untilMillis, 0), COALESCE(s.untilMillis, 0)) END,
            p.occurrenceAt ASC, p.id ASC LIMIT 1
    """)
    suspend fun nextReadyMessage(now: Long): ReadyMessage?

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

    // QQ official bot operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQqBot(bot: QqBot): Long

    @Update
    suspend fun updateQqBot(bot: QqBot)

    @Delete
    suspend fun deleteQqBot(bot: QqBot)

    @Query("SELECT * FROM qq_bots ORDER BY id ASC")
    fun getAllQqBots(): Flow<List<QqBot>>

    @Query("SELECT * FROM qq_bots WHERE id = :id")
    suspend fun getQqBotById(id: Long): QqBot?

    @Query("SELECT * FROM qq_bots WHERE appId = :appId AND groupOpenId = :groupOpenId LIMIT 1")
    suspend fun getQqBotByTarget(appId: String, groupOpenId: String): QqBot?

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
          AND ((:qqBotId IS NULL AND qqBotId IS NULL) OR qqBotId = :qqBotId)
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
        qqBotId: Long?,
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

    @Query("SELECT * FROM task_logs WHERE pendingMessageId = :pendingMessageId ORDER BY CASE WHEN status = 'SUCCESS' THEN 0 ELSE 1 END, id DESC LIMIT 1")
    suspend fun getLogForPendingMessage(pendingMessageId: Long): TaskLog?

    @Transaction
    suspend fun savePendingMessageLog(log: TaskLog) {
        val pendingId = requireNotNull(log.pendingMessageId)
        val previous = getLogForPendingMessage(pendingId)
        if (previous == null) {
            insertLog(log)
        } else if (previous.status != com.autosend.data.models.LogStatus.SUCCESS) {
            updateLog(log.copy(id = previous.id, isRead = previous.isRead &&
                previous.status == log.status && previous.errorMessage == log.errorMessage))
        }
    }

    // 旧版重试已产生的重复记录只合并展示，原始历史数据仍保留。
    @Query("""
        SELECT l.* FROM task_logs l
        WHERE l.pendingMessageId IS NULL OR l.id IN (
            SELECT COALESCE(MAX(CASE WHEN status = 'SUCCESS' THEN id END), MAX(id))
            FROM task_logs WHERE pendingMessageId IS NOT NULL GROUP BY pendingMessageId
        )
        ORDER BY l.timestamp DESC, l.id DESC LIMIT 500
    """)
    fun getAllLogs(): Flow<List<TaskLog>>

    @Query("SELECT * FROM task_logs WHERE id = :id")
    suspend fun getLogById(id: Long): TaskLog?

    @Query("UPDATE task_logs SET isRead = 1 WHERE isRead = 0 AND status != 'SYSTEM'")
    suspend fun markAllVisibleLogsAsRead()

    @Query("DELETE FROM task_logs WHERE status != 'SYSTEM'")
    suspend fun clearVisibleLogs()

}
