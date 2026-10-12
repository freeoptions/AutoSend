package com.autosend.utils

import com.autosend.data.models.DeliveryGate
import com.autosend.data.models.PendingMessage

data class PendingQueuePresentation(
    val status: String,
    val explanation: String?,
    val nextCheckAt: Long? = null,
    val connectionProbe: Boolean = false,
    val error: String? = null,
    val canRetry: Boolean = false,
    val canEdit: Boolean = false
)

/** 与 nextReadyMessage 使用相同的冷却时间；展示的是最早可尝试时间，不承诺准确送达时间。 */
fun pendingQueuePresentation(message: PendingMessage, gates: List<DeliveryGate>, taskEnabled: Boolean?,
                             now: Long): PendingQueuePresentation {
    val relevant = gates.filter { it.scope in setOf("network", "bot:${message.botId}",
        "chat:${message.botId}:${message.chatId}", "pause") }
    val network = relevant.find { it.scope == "network" }
    val permanent = relevant.find { it.untilMillis == Long.MAX_VALUE && it.scope != "pause" }
    val error = permanent?.lastError ?: message.lastError ?: network?.lastError
    val deleted = message.lastError?.startsWith("原任务已删除") == true
    val editable = message.state != PendingMessage.SENDING && !deleted
    if (message.state == PendingMessage.SENDING) {
        return PendingQueuePresentation("正在发送", "正在等待 Telegram 确认")
    }
    if (deleted) return PendingQueuePresentation("需要处理", "原任务已删除，请确认后取消这条消息")
    if (relevant.any { it.scope == "pause" && it.untilMillis == Long.MAX_VALUE }) {
        return PendingQueuePresentation("已暂停", "恢复发送后继续处理", error = error, canEdit = editable)
    }
    if (taskEnabled == false && !message.manual) {
        return PendingQueuePresentation("已暂停", "启用原任务后继续发送", error = error, canEdit = editable)
    }
    if (message.state == PendingMessage.BLOCKED || permanent != null) {
        return PendingQueuePresentation("需要处理", "检查配置或编辑消息后重试", error = error,
            canRetry = true, canEdit = editable)
    }
    val readyAt = maxOf(message.nextAttemptAt, message.leaseUntil, relevant.maxOfOrNull { it.untilMillis } ?: 0)
    val retrying = message.attempts > 0 || (network?.failures ?: 0) > 0
    return PendingQueuePresentation(
        status = if (retrying) "等待重试" else "排队中",
        explanation = if (readyAt <= now) "等待队列依次处理" else null,
        nextCheckAt = readyAt.takeIf { it > now },
        connectionProbe = (network?.failures ?: 0) >= 3 && network?.untilMillis == readyAt,
        error = error, canRetry = true, canEdit = editable
    )
}
