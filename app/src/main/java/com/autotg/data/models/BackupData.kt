package com.autotg.data.models

import com.google.gson.annotations.SerializedName

data class BackupData(
    @SerializedName("bots")
    val bots: List<BotBackup>,
    @SerializedName("chats")
    val chats: List<ChatBackup>,
    @SerializedName("feishuWebhooks")
    val feishuWebhooks: List<FeishuWebhookBackup>? = emptyList(),
    @SerializedName("tasks")
    val tasks: List<TaskBackup>
)

data class BotBackup(
    val name: String,
    val token: String
)

data class ChatBackup(
    val name: String,
    val chatId: String
)

data class FeishuWebhookBackup(
    val name: String,
    val webhookUrl: String,
    val secret: String? = null
)

data class TaskBackup(
    val botName: String,
    val chatName: String,
    val deliveryChannel: String? = null,
    val feishuWebhookName: String? = null,
    val content: String,
    val parseMode: String? = null,
    val scheduledTime: Long,
    val isEnabled: Boolean,
    val cronExpression: String?,
    val lunarMonth: Int? = null,
    val lunarDay: Int? = null,
    val lunarLeapMonth: Boolean = false
)
