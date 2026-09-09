package com.autosend.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "feishu_webhooks")
data class FeishuWebhook(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val webhookUrl: String,
    val secret: String? = null
)

/** Telegram 仅保留用于读取旧数据库记录，当前应用只创建和调度飞书任务。 */
enum class DeliveryChannel {
    TELEGRAM,
    FEISHU
}
