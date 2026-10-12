package com.autosend.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Embedded

/** 每次触发的独立快照；不关联级联删除，清理日志或删除任务不能悄悄丢掉消息。 */
@Entity(tableName = "pending_messages", indices = [Index(value = ["occurrenceKey"], unique = true)])
data class PendingMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceKey: String,
    val taskId: Long,
    val botId: Long,
    val chatId: Long,
    val content: String,
    val parseMode: MessageParseMode,
    val occurrenceAt: Long,
    val manual: Boolean = false,
    val state: String = WAITING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val leaseUntil: Long = 0,
    val lastError: String? = null,
    val messageId: Long? = null,
    val sentAt: Long? = null
) {
    companion object {
        const val WAITING = "WAITING"
        const val SENDING = "SENDING"
        const val BLOCKED = "BLOCKED"
        const val SENT = "SENT"
        const val CANCELLED = "CANCELLED"
    }
}

@Entity(tableName = "delivery_gates")
data class DeliveryGate(
    @PrimaryKey val scope: String,
    val untilMillis: Long = 0,
    val failures: Int = 0,
    val lastError: String? = null
)

data class ReadyMessage(@Embedded val message: PendingMessage, val readyAt: Long)
