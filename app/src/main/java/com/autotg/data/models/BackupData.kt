package com.autotg.data.models

import com.google.gson.annotations.SerializedName

data class BackupData(
    @SerializedName("bots")
    val bots: List<BotBackup>,
    @SerializedName("chats")
    val chats: List<ChatBackup>,
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

data class TaskBackup(
    val botName: String,
    val chatName: String,
    val content: String,
    val parseMode: String? = null,
    val scheduledTime: Long,
    val isEnabled: Boolean,
    val cronExpression: String?
)
