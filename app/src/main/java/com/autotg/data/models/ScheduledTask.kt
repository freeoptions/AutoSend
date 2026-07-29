package com.autotg.data.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_tasks",
    foreignKeys = [
        ForeignKey(
            entity = Bot::class,
            parentColumns = ["id"],
            childColumns = ["botId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Chat::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("botId"), Index("chatId")]
)
data class ScheduledTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val botId: Long,
    val chatId: Long,
    val content: String,
    val parseMode: MessageParseMode = MessageParseMode.NONE,
    val scheduledTime: Long,
    val status: TaskStatus = TaskStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val isEnabled: Boolean = true,
    val cronExpression: String? = null // null means one-time task
)

enum class TaskStatus {
    PENDING,
    SUCCESS,
    FAILED
}

enum class MessageParseMode {
    NONE,
    MARKDOWN_V2;

    fun toTelegramValue(): String? {
        return when (this) {
            NONE -> null
            MARKDOWN_V2 -> "MarkdownV2"
        }
    }
}
