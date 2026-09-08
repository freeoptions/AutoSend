package com.autotg.utils

import android.content.Context
import com.autotg.data.models.LogStatus
import com.autotg.data.models.DeliveryChannel
import com.autotg.data.models.ScheduledTask
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

    suspend fun execute(
        taskId: Long,
        triggerAtMillis: Long = 0L,
        forceExecution: Boolean = false
    ): TaskSendOutcome {
        return taskMutexes.getOrPut(taskId) { Mutex() }.withLock {
            executeLocked(taskId, triggerAtMillis, forceExecution)
        }
    }

    private suspend fun executeLocked(
        taskId: Long,
        triggerAtMillis: Long,
        forceExecution: Boolean
    ): TaskSendOutcome {
        val task = repository.getTaskById(taskId) ?: return TaskSendOutcome.SKIPPED
        if (task.deliveryChannel != DeliveryChannel.FEISHU) return TaskSendOutcome.SKIPPED
        if (forceExecution) {
            return if (task.isEnabled) {
                executeRecurringRetry(task, triggerAtMillis)
            } else {
                TaskSendOutcome.SKIPPED
            }
        }

        return when (TaskExecutionPolicy.decide(task)) {
            TaskExecutionDecision.SkipDisabled,
            TaskExecutionDecision.SkipAlreadyCompleted -> TaskSendOutcome.SKIPPED

            TaskExecutionDecision.AdvanceCompletedRecurring -> {
                scheduleNextRecurringTask(
                    task = task,
                    currentOccurrenceAt = task.scheduledTime,
                    failureMessage = null
                )
                TaskSendOutcome.RESCHEDULED
            }

            TaskExecutionDecision.RescheduleFuture -> {
                WorkManagerHelper.scheduleTask(context, task)
                TaskSendOutcome.RESCHEDULED
            }

            TaskExecutionDecision.ExecuteNow -> executeDueTask(
                task = task,
                triggerAtMillis = triggerAtMillis.takeIf { it > 0L } ?: task.scheduledTime
            )
        }
    }

    private suspend fun executeDueTask(
        task: ScheduledTask,
        triggerAtMillis: Long
    ): TaskSendOutcome {
        val isRecurring = RecurringScheduleUtils.isRecurring(task)

        // 重复任务必须先持久化并提交下一次，再触碰本次网络发送，避免异常打断调度链。
        if (isRecurring) {
            scheduleNextRecurringTask(
                task = task,
                currentOccurrenceAt = triggerAtMillis,
                failureMessage = null
            )
        }

        TaskExecutionLogger.record(
            context,
            task.id,
            TaskExecutionLogger.STAGE_TASK_STARTED,
            "triggerAtMillis=$triggerAtMillis，通道=${task.deliveryChannel.name}，来源=${if (isRecurring) "重复任务" else "单次任务"}"
        )

        val target = repository.describeTaskTarget(task)
        val result = repository.sendScheduledMessage(
            taskId = task.id,
            updateTaskState = !isRecurring
        )
        val failureMessage = result.exceptionOrNull()?.message ?: "未知错误"

        if (result.isSuccess) {
            if (isRecurring) {
                val nextTask = repository.getTaskById(task.id)
                if (nextTask != null) {
                    repository.updateTask(
                        nextTask.copy(
                            status = TaskStatus.PENDING,
                            retryCount = 0,
                            lastError = null
                        )
                    )
                }
            }
            repository.insertLog(
                TaskLog(
                    taskId = task.id,
                    taskContent = task.content,
                    botName = target.channelName,
                    chatName = target.targetName,
                    status = LogStatus.SUCCESS,
                    errorMessage = "${TaskExecutionLogger.STAGE_TASK_SUCCEEDED}；triggerAtMillis=$triggerAtMillis"
                )
            )
            return TaskSendOutcome.SENT
        }

        if (isRecurring) {
            val nextTask = repository.getTaskById(task.id)
            if (nextTask != null) {
                repository.updateTask(
                    nextTask.copy(
                        status = TaskStatus.PENDING,
                        retryCount = 1,
                        lastError = failureMessage
                    )
                )
            }
            repository.insertLog(
                TaskLog(
                    taskId = task.id,
                    taskContent = task.content,
                    botName = target.channelName,
                    chatName = target.targetName,
                    status = LogStatus.FAILED,
                    errorMessage = "${TaskExecutionLogger.STAGE_TASK_FAILED}；本次触发一分钟后补偿重试，下一次 Cron 闹钟不受影响。原因: $failureMessage"
                )
            )
            WorkManagerHelper.enqueueRecurringRetry(
                context = context,
                taskId = task.id,
                occurrenceAtMillis = triggerAtMillis,
                delayMillis = RETRY_DELAY_MS
            )
            return TaskSendOutcome.RETRY_SCHEDULED
        }

        val latestTask = repository.getTaskById(task.id) ?: task
        if (latestTask.retryCount < MAX_ATTEMPTS) {
            repository.insertLog(
                TaskLog(
                    taskId = task.id,
                    taskContent = task.content,
                    botName = target.channelName,
                    chatName = target.targetName,
                    status = LogStatus.FAILED,
                    errorMessage = "${TaskExecutionLogger.STAGE_TASK_FAILED}；首次尝试失败，一分钟后将重试。原因: $failureMessage"
                )
            )
            WorkManagerHelper.scheduleRetry(context, task.id, RETRY_DELAY_MS)
            return TaskSendOutcome.RETRY_SCHEDULED
        }

        repository.insertLog(
            TaskLog(
                taskId = task.id,
                taskContent = task.content,
                botName = target.channelName,
                chatName = target.targetName,
                status = LogStatus.FAILED,
                errorMessage = "${TaskExecutionLogger.STAGE_TASK_FAILED}；最终重试失败，停止重试。原因: $failureMessage"
            )
        )
        val finalTask = repository.getTaskById(task.id) ?: task
        repository.updateTask(finalTask.copy(status = TaskStatus.FAILED))
        return TaskSendOutcome.FINAL_FAILURE
    }

    private suspend fun executeRecurringRetry(
        task: ScheduledTask,
        triggerAtMillis: Long
    ): TaskSendOutcome {
        TaskExecutionLogger.record(
            context,
            task.id,
            TaskExecutionLogger.STAGE_TASK_STARTED,
            "重复任务补偿重试；通道=${task.deliveryChannel.name}，原触发时间=$triggerAtMillis"
        )
        val target = repository.describeTaskTarget(task)
        val result = repository.sendScheduledMessage(task.id, updateTaskState = false)
        val latestTask = repository.getTaskById(task.id) ?: task

        if (result.isSuccess) {
            repository.updateTask(latestTask.copy(retryCount = 0, lastError = null))
            repository.insertLog(
                TaskLog(
                    taskId = task.id,
                    taskContent = task.content,
                    botName = target.channelName,
                    chatName = target.targetName,
                    status = LogStatus.SUCCESS,
                    errorMessage = "${TaskExecutionLogger.STAGE_TASK_SUCCEEDED}；重复任务补偿重试成功"
                )
            )
            return TaskSendOutcome.SENT
        }

        val failureMessage = result.exceptionOrNull()?.message ?: "未知错误"
        repository.updateTask(latestTask.copy(retryCount = 0, lastError = failureMessage))
        repository.insertLog(
            TaskLog(
                taskId = task.id,
                taskContent = task.content,
                botName = target.channelName,
                chatName = target.targetName,
                status = LogStatus.FAILED,
                errorMessage = "${TaskExecutionLogger.STAGE_TASK_FAILED}；重复任务补偿重试失败，下一次重复调度仍保留。原因: $failureMessage"
            )
        )
        return TaskSendOutcome.FINAL_FAILURE
    }

    private suspend fun scheduleNextRecurringTask(
        task: ScheduledTask,
        currentOccurrenceAt: Long,
        failureMessage: String?
    ) {
        if (!task.isEnabled || !RecurringScheduleUtils.isRecurring(task)) return

        val searchFrom = maxOf(System.currentTimeMillis() + 1_000L, currentOccurrenceAt + 1_000L)
        val nextTime = RecurringScheduleUtils.getNextExecutionTimeFrom(task, searchFrom) ?: return
        val latestTask = repository.getTaskById(task.id) ?: return

        // 可能是 WorkManager 补偿重复进入；已经推进到更晚时间时不重复后移。
        if (latestTask.scheduledTime > currentOccurrenceAt) return

        val nextTask = latestTask.copy(
            scheduledTime = nextTime,
            status = TaskStatus.PENDING,
            retryCount = 0,
            lastError = failureMessage
        )
        repository.updateTask(nextTask)
        val exactScheduled = WorkManagerHelper.scheduleTask(context, nextTask)
        TaskExecutionLogger.record(
            context,
            task.id,
            TaskExecutionLogger.STAGE_NEXT_SCHEDULE_SUBMITTED,
            "nextTriggerAtMillis=$nextTime，exactAlarm=$exactScheduled，重复规则=${if (task.isLunarRecurring) "阴历" else "Cron"}，WorkManagerCompensation=true"
        )
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
