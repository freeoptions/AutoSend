package com.autosend.utils

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.autosend.data.local.AppDatabase
import com.autosend.data.models.*
import com.autosend.data.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramQueueProcessor @Inject constructor(
    private val repository: TelegramRepository,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private val scheduledTimes = mutableMapOf<Long, Long>()
    private val dao get() = repository.queueDao

    suspend fun drain(budgetMillis: Long = 4 * 60_000L) {
        // 前台服务和 WorkManager 共用一个消费者；已有消费者时不等待、不重复发送。
        if (!mutex.tryLock()) return
        try {
            val deadline = SystemClock.elapsedRealtime() + budgetMillis
            do {
                val moreDue = repository.captureDueTelegramTasks()
                restoreFutureSchedules()
                while (SystemClock.elapsedRealtime() < deadline) {
                    val now = System.currentTimeMillis()
                    val next = dao.nextReadyMessage(now) ?: break
                    val wait = next.readyAt - now
                    if (wait > 5_000L) {
                        TelegramQueueScheduler.wakeAt(context, next.readyAt)
                        return
                    }
                    if (wait > 0) {
                        delay(wait)
                        continue
                    }
                    val message = next.message
                    val gate = dao.getDeliveryGate("network")
                    if ((gate?.failures ?: 0) >= 3) {
                        val failure = repository.telegramClient.probe(message.botId)
                        if (failure != null) {
                            handleFailure(message, failure, attempted = false)
                            continue
                        }
                    }
                    // 探测期间用户可能取消、编辑或暂停消息；事务内重新读取后才领取。
                    val sending = database.withTransaction {
                        val currentTime = System.currentTimeMillis()
                        val candidate = dao.nextReadyMessage(currentTime)
                        if (candidate == null || candidate.message.id != message.id || candidate.readyAt > currentTime) {
                            null
                        } else candidate.message.copy(state = PendingMessage.SENDING,
                            attempts = candidate.message.attempts + 1, leaseUntil = currentTime + 60_000L)
                            .also { dao.updatePendingMessage(it) }
                    } ?: continue
                    when (val result = repository.telegramClient.send(sending)) {
                        is TelegramDeliveryResult.Success -> handleSuccess(sending, result)
                        is TelegramDeliveryResult.Failure -> handleFailure(sending, result, attempted = true)
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    TelegramQueueScheduler.wakeAt(context, System.currentTimeMillis() + 2_000L)
                    return
                }
            } while (moreDue)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun restoreFutureSchedules() {
        val tasks = repository.getAllTasks().first().filter {
            it.deliveryChannel == DeliveryChannel.TELEGRAM && it.isEnabled && it.scheduledTime > System.currentTimeMillis()
        }
        scheduledTimes.keys.retainAll(tasks.mapTo(HashSet()) { it.id })
        for (task in tasks) {
            if (scheduledTimes[task.id] == task.scheduledTime) continue
            // 每个进程首次恢复、或触发游标变化时才重建，不让网络重试刷全部调度日志。
            WorkManagerHelper.scheduleTask(context, task)
            scheduledTimes[task.id] = task.scheduledTime
        }
    }

    private suspend fun handleSuccess(message: PendingMessage, result: TelegramDeliveryResult.Success) = database.withTransaction {
        val now = System.currentTimeMillis()
        dao.updatePendingMessage(message.copy(state = PendingMessage.SENT, leaseUntil = 0,
            messageId = result.messageId, sentAt = now, lastError = null))
        dao.putDeliveryGate(DeliveryGate("network"))
        dao.putDeliveryGate(DeliveryGate("bot:${message.botId}", now + 40L))
        val chat = dao.getChatById(message.chatId)
        dao.putDeliveryGate(DeliveryGate("chat:${message.botId}:${message.chatId}", now +
            TelegramRetryPolicy.messageIntervalMillis(result.chatType, chat?.chatId.orEmpty())))
        val task = dao.getTaskById(message.taskId)
        if (!message.manual && task != null && !RecurringScheduleUtils.isRecurring(task) && task.scheduledTime == message.occurrenceAt) {
            dao.updateTask(task.copy(status = TaskStatus.SUCCESS, retryCount = 0, lastError = null))
        }
        dao.savePendingMessageLog(TaskLog(taskId = message.taskId, taskContent = message.content,
            botName = "Telegram", chatName = chat?.name ?: "已删除的目标", status = LogStatus.SUCCESS,
            timestamp = now, pendingMessageId = message.id))
    }

    private suspend fun handleFailure(message: PendingMessage, failure: TelegramDeliveryResult.Failure, attempted: Boolean) {
        val alert = database.withTransaction {
            val now = System.currentTimeMillis()
            val current = dao.getPendingMessage(message.id) ?: return@withTransaction false
            if (current.state == PendingMessage.CANCELLED ||
                (attempted && current.state == PendingMessage.BLOCKED)) return@withTransaction false
            var shouldAlert = attempted && current.attempts == 3 &&
                failure.kind != DeliveryFailureKind.NETWORK && failure.migratedChatId == null
            val error = if (failure.uncertain) "结果未确认：${failure.reason}" else failure.reason
            var updated = current.copy(state = PendingMessage.WAITING, leaseUntil = 0,
                lastError = error)
            if (failure.migratedChatId != null) {
                dao.getChatById(message.chatId)?.let { dao.updateChat(it.copy(chatId = failure.migratedChatId.toString())) }
                updated = updated.copy(nextAttemptAt = now + 5_000L)
            } else when (failure.kind) {
                DeliveryFailureKind.NETWORK -> {
                    val gate = dao.getDeliveryGate("network") ?: DeliveryGate("network")
                    val count = (gate.failures + 1).coerceAtMost(100)
                    dao.putDeliveryGate(DeliveryGate("network", now + TelegramRetryPolicy.networkDelayMillis(count), count, error))
                    // 共享线路第三次实际发送失败时提醒；后续探测不发送消息，也不重复通知。
                    shouldAlert = attempted && count == 3
                }
                DeliveryFailureKind.RATE_LIMIT -> dao.putDeliveryGate(DeliveryGate("bot:${message.botId}",
                    now + failure.retryAfterSeconds.coerceIn(1, 2_592_000) * 1_000L + 1_000L, lastError = error))
                DeliveryFailureKind.BOT -> dao.putDeliveryGate(DeliveryGate("bot:${message.botId}", Long.MAX_VALUE, lastError = error))
                DeliveryFailureKind.TARGET -> dao.putDeliveryGate(DeliveryGate("chat:${message.botId}:${message.chatId}", Long.MAX_VALUE, lastError = error))
                DeliveryFailureKind.MESSAGE -> updated = updated.copy(state = PendingMessage.BLOCKED)
            }
            dao.updatePendingMessage(updated)
            // 一次发送只保留一条展示记录；重试更新原因和时间，成功后原地变为成功。
            if (attempted || current.lastError != error) {
                dao.savePendingMessageLog(TaskLog(taskId = message.taskId, taskContent = message.content,
                    botName = "Telegram", chatName = dao.getChatById(message.chatId)?.name ?: "未知目标",
                    status = LogStatus.FAILED, errorMessage = "$error；消息保留在待发队列",
                    timestamp = now, pendingMessageId = message.id))
            }
            shouldAlert
        }
        // 先提交队列和冷却状态，再通知用户。
        if (alert) TelegramDeliveryAlerts.failed(context)
    }
}
