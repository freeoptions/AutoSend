package com.autosend.data.models

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
        ),
        ForeignKey(
            entity = QqBot::class,
            parentColumns = ["id"],
            childColumns = ["qqBotId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("botId"), Index("chatId"), Index("feishuWebhookId"), Index("qqBotId")]
)
data class ScheduledTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deliveryChannel: DeliveryChannel = DeliveryChannel.TELEGRAM,
    val botId: Long? = null,
    val chatId: Long? = null,
    val feishuWebhookId: Long? = null,
    val qqBotId: Long? = null,
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

data class DeliveryTarget(
    val channel: DeliveryChannel,
    val id: Long,
    val name: String
) {
    val channelName: String
        get() = when (channel) {
            DeliveryChannel.QQ -> "QQ 群"
            DeliveryChannel.FEISHU -> "飞书"
            DeliveryChannel.TELEGRAM -> "Telegram"
        }

    fun matches(task: ScheduledTask): Boolean = when (channel) {
        DeliveryChannel.QQ -> task.deliveryChannel == channel && task.qqBotId == id
        DeliveryChannel.FEISHU -> task.deliveryChannel == channel && task.feishuWebhookId == id
        DeliveryChannel.TELEGRAM -> task.deliveryChannel == channel && task.chatId == id
    }
}
