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
        ),
        ForeignKey(
            entity = FeishuWebhook::class,
            parentColumns = ["id"],
            childColumns = ["feishuWebhookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("botId"), Index("chatId"), Index("feishuWebhookId")]
)
data class ScheduledTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deliveryChannel: DeliveryChannel = DeliveryChannel.TELEGRAM,
    val botId: Long? = null,
    val chatId: Long? = null,
    val feishuWebhookId: Long? = null,
    val content: String,
    val parseMode: MessageParseMode = MessageParseMode.NONE,
    val scheduledTime: Long,
    val status: TaskStatus = TaskStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val isEnabled: Boolean = true,
    val cronExpression: String? = null,
    val lunarMonth: Int? = null,
    val lunarDay: Int? = null,
    val lunarLeapMonth: Boolean = false
) {
    val isLunarRecurring: Boolean
        get() = lunarMonth != null && lunarDay != null
}

enum class TaskStatus {
    PENDING,
    SUCCESS,
    FAILED
}

enum class MessageParseMode {
    NONE,
    MARKDOWN_V2
}
