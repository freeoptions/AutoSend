package com.autotg.utils

import android.content.Context
import com.autotg.data.models.LogStatus
import com.autotg.data.models.TaskLog
import com.autotg.data.models.TaskStatus
import com.autotg.data.repository.TelegramRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskSendExecutor @Inject constructor(
    private val repository: TelegramRepository,
    @ApplicationContext private val context: Context
) {
    private val taskMutexes = ConcurrentHashMap<Long, Mutex>()

    suspend fun execute(taskId: Long): TaskSendOutcome {
        return taskMutexes.getOrPut(taskId) { Mutex() }.withLock {
            executeLocked(taskId)
        }
    }

    private suspend fun executeLocked(taskId: Long): TaskSendOutcome {
        val task = repository.getTaskById(taskId) ?: return TaskSendOutcome.SKIPPED

        return when (TaskExecutionPolicy.decide(task)) {
            TaskExecutionDecision.SkipDisabled,
            TaskExecutionDecision.SkipAlreadyCompleted -> TaskSendOutcome.SKIPPED

            TaskExecutionDecision.AdvanceCompletedRecurring -> {
                scheduleNextRecurringTaskIfNeeded(taskId, failureMessage = null)
                TaskSendOutcome.RESCHEDULED
            }

            TaskExecutionDecision.RescheduleFuture -> {
                WorkManagerHelper.scheduleTask(context, task)
                TaskSendOutcome.RESCHEDULED
            }

            TaskExecutionDecision.ExecuteNow -> executeDueTask(taskId)
        }
    }

    private suspend fun executeDueTask(taskId: Long): TaskSendOutcome {
        val task = repository.getTaskById(taskId) ?: return TaskSendOutcome.SKIPPED
        val bot = repository.getBotById(task.botId)
        val chat = repository.getChatById(task.chatId)

        val result = repository.sendScheduledMessage(taskId)
        val failureMessage = result.exceptionOrNull()?.message ?: "未知错误"

        if (result.isSuccess) {
            repository.insertLog(
                TaskLog(
                    taskId = taskId,
                    taskContent = task.content,
                    botName = bot?.name ?: "未知机器人",
                    chatName = chat?.name ?: "未知群组",
                    status = LogStatus.SUCCESS
                )
            )
            scheduleNextRecurringTaskIfNeeded(taskId, failureMessage = null)
            return TaskSendOutcome.SENT
        }

        val latestTask = repository.getTaskById(taskId) ?: task
        if (latestTask.retryCount < MAX_ATTEMPTS) {
            repository.insertLog(
                TaskLog(
                    taskId = taskId,
                    taskContent = task.content,
                    botName = bot?.name ?: "未知机器人",
                    chatName = chat?.name ?: "未知群组",
                    status = LogStatus.FAILED,
                    errorMessage = "首次尝试失败，一分钟后将重试。原因: $failureMessage"
                )
            )
            WorkManagerHelper.scheduleRetry(context, taskId, RETRY_DELAY_MS)
            return TaskSendOutcome.RETRY_SCHEDULED
        }

        repository.insertLog(
            TaskLog(
                taskId = taskId,
                taskContent = task.content,
                botName = bot?.name ?: "未知机器人",
                chatName = chat?.name ?: "未知群组",
                status = LogStatus.FAILED,
                errorMessage = "最终重试失败，停止重试。原因: $failureMessage"
            )
        )
        scheduleNextRecurringTaskIfNeeded(taskId, failureMessage)

        val finalTask = repository.getTaskById(taskId) ?: task
        if (finalTask.cronExpression.isNullOrBlank()) {
            repository.updateTask(finalTask.copy(status = TaskStatus.FAILED))
        }

        return TaskSendOutcome.FINAL_FAILURE
    }

    private suspend fun scheduleNextRecurringTaskIfNeeded(
        taskId: Long,
        failureMessage: String?
    ) {
        val latestTask = repository.getTaskById(taskId) ?: return
        val cron = latestTask.cronExpression?.takeIf { it.isNotBlank() } ?: return
        if (!latestTask.isEnabled) return

        val nextTime = CronUtils.getNextExecutionTimes(cron, 1).firstOrNull()
            ?: CronUtils.getNextExecutionTimeFrom(cron, System.currentTimeMillis() + 1000L)
            ?: return

        val nextTask = latestTask.copy(
            scheduledTime = nextTime,
            status = TaskStatus.PENDING,
            retryCount = 0,
            lastError = failureMessage
        )
        repository.updateTask(nextTask)
        WorkManagerHelper.scheduleTask(context, nextTask)
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 60_000L
    }
}

enum class TaskSendOutcome {
    SENT,
    RETRY_SCHEDULED,
    FINAL_FAILURE,
    RESCHEDULED,
    SKIPPED
}
