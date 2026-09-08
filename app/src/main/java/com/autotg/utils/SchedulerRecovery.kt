package com.autotg.utils

import android.content.Context
import com.autotg.data.models.LogStatus
import com.autotg.data.models.DeliveryChannel
import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskLog
import com.autotg.data.models.TaskStatus
import com.autotg.data.repository.TelegramRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SchedulerRecovery {
    private const val PREFS_NAME = "autotg_scheduler_recovery"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"
    private const val MISSED_PREFIX = "\u68c0\u6d4b\u5230\u7a97\u53e3\u5185\u5230\u671f\u4efb\u52a1"
    private val recoveryMutex = Mutex()

    suspend fun recoverEnabledTasks(context: Context, repository: TelegramRepository) {
        recoveryMutex.withLock {
            recoverEnabledTasksLocked(context, repository)
        }
    }

    private suspend fun recoverEnabledTasksLocked(context: Context, repository: TelegramRepository) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, now)

        repository.getAllTasks()
            .first()
            .filter { it.isEnabled && it.deliveryChannel == DeliveryChannel.FEISHU }
            .forEach { task ->
                recoverTask(context, repository, task, lastCheckTime, now)
            }

        prefs.edit().putLong(KEY_LAST_CHECK_TIME, now).apply()
    }

    private suspend fun recoverTask(
        context: Context,
        repository: TelegramRepository,
        task: ScheduledTask,
        lastCheckTime: Long,
        now: Long
    ) {
        val isRecurring = RecurringScheduleUtils.isRecurring(task)
        val scheduledTime = task.scheduledTime

        if (scheduledTime <= 0L) {
            recoverInvalidTimeTask(context, repository, task)
            return
        }

        if (scheduledTime > now) {
            WorkManagerHelper.scheduleTask(context, task)
            return
        }

        val shouldRecover = scheduledTime in (lastCheckTime + 1)..now
        if (!shouldRecover) {
            if (isRecurring) {
                recoverRecurringTaskAfterLongGap(context, repository, task, now)
            } else if (task.status == TaskStatus.PENDING) {
                val message = "$MISSED_PREFIX\uff1a${formatTime(scheduledTime)} \u5230\u671f\uff0c\u56e0\u9519\u8fc7\u68c0\u67e5\u65f6\u673a\uff0c\u5df2\u5728\u672c\u6b21\u5524\u9192\u540e\u8865\u53d1\u3002"
                insertMissedLog(repository, task, message)
                repository.updateTask(task.copy(lastError = message))
                WorkManagerHelper.runTaskNow(context, task.id)
            }
            return
        }

        if (!isRecurring) {
            if (task.status == TaskStatus.PENDING) {
                val message = "$MISSED_PREFIX\uff1a${formatTime(scheduledTime)} \u5230\u671f\uff0c\u5df2\u5728\u672c\u6b21\u68c0\u67e5\u7a97\u53e3\u5185\u8865\u53d1\u3002"
                insertMissedLog(repository, task, message)
                repository.updateTask(task.copy(lastError = message))
                WorkManagerHelper.runTaskNow(context, task.id)
            }
            return
        }

        if (task.status == TaskStatus.PENDING) {
            val message = "$MISSED_PREFIX\uff1a${formatTime(scheduledTime)} \u5230\u671f\uff0c\u5df2\u5728\u672c\u6b21\u68c0\u67e5\u7a97\u53e3\u5185\u8865\u53d1\u3002"
            if (task.lastError?.startsWith(MISSED_PREFIX) != true) {
                insertMissedLog(repository, task, message)
            }
            repository.updateTask(task.copy(lastError = message))
            WorkManagerHelper.runTaskNow(context, task.id)
            return
        }

        recoverRecurringTaskAfterLongGap(context, repository, task, now)
    }

    private suspend fun recoverRecurringTaskAfterLongGap(
        context: Context,
        repository: TelegramRepository,
        task: ScheduledTask,
        now: Long
    ) {
        val nextTime = RecurringScheduleUtils.getNextExecutionTimeFrom(task, now + 1_000L)

        if (nextTime != null) {
            val message = "\u5df2\u8d85\u8fc7\u8865\u53d1\u7a97\u53e3\uff0c\u8df3\u8fc7\u8fc7\u671f\u89e6\u53d1\uff0c\u76f4\u63a5\u8ba1\u7b97\u4e0b\u4e00\u6b21\u65f6\u95f4\u3002"
            if (task.lastError?.startsWith(MISSED_PREFIX) != true) {
                insertMissedLog(repository, task, message)
            }
            val nextTask = task.copy(
                scheduledTime = nextTime,
                status = TaskStatus.PENDING,
                retryCount = 0,
                lastError = message
            )
            repository.updateTask(nextTask)
            WorkManagerHelper.scheduleTask(context, nextTask)
        }
    }

    private suspend fun recoverInvalidTimeTask(
        context: Context,
        repository: TelegramRepository,
        task: ScheduledTask
    ) {
        if (RecurringScheduleUtils.isRecurring(task)) {
            val nextTime = RecurringScheduleUtils.getNextExecutionTimeFrom(
                task,
                System.currentTimeMillis() + 1_000L
            )
            if (nextTime != null) {
                val nextTask = task.copy(
                    scheduledTime = nextTime,
                    status = TaskStatus.PENDING,
                    retryCount = 0,
                    lastError = null
                )
                repository.updateTask(nextTask)
                WorkManagerHelper.scheduleTask(context, nextTask)
            }
            return
        }

        if (task.status == TaskStatus.PENDING) {
            val message = "\u4efb\u52a1\u65f6\u95f4\u65e0\u6548\uff0c\u8bf7\u91cd\u65b0\u7f16\u8f91\u4efb\u52a1\u5e76\u8bbe\u7f6e\u53d1\u9001\u65f6\u95f4\u3002"
            insertMissedLog(repository, task, message)
            repository.updateTask(task.copy(status = TaskStatus.FAILED, lastError = message))
        }
    }

    private suspend fun insertMissedLog(
        repository: TelegramRepository,
        task: ScheduledTask,
        message: String
    ) {
        val target = repository.describeTaskTarget(task)
        repository.insertLog(
            TaskLog(
                taskId = task.id,
                taskContent = task.content,
                botName = target.channelName,
                chatName = target.targetName,
                status = LogStatus.MISSED,
                errorMessage = message
            )
        )
    }

    private fun formatTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
    }
}
