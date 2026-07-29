package com.autotg.utils

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autotg.data.models.ScheduledTask
import com.autotg.service.TaskExecutionService
import com.autotg.worker.TelegramWorker
import java.util.concurrent.TimeUnit

object WorkManagerHelper {
    fun scheduleTask(context: Context, task: ScheduledTask) {
        val now = System.currentTimeMillis()
        val delay = task.scheduledTime - now
        if (!task.isEnabled) {
            cancelTask(context, task.id)
            return
        }

        if (delay <= 0 && task.cronExpression == null) {
            runTaskNow(context, task.id)
            return
        }

        if (delay <= 0L) {
            runTaskNow(context, task.id)
            return
        }

        val exactScheduled = TaskAlarmScheduler.scheduleExactTask(
            context = context,
            taskId = task.id,
            triggerAtMillis = task.scheduledTime
        )
        RecoveryScheduler.ensureRecoveryWork(context)
        RecoveryScheduler.scheduleTaskPreflight(context, task.scheduledTime)
        val backupDelay = if (exactScheduled) delay + WORK_BACKUP_GRACE_MS else delay
        enqueueTaskWork(context, task.id, backupDelay)
    }

    fun scheduleRetry(context: Context, taskId: Long, delayMillis: Long) {
        if (delayMillis <= 0L) {
            runTaskNow(context, taskId)
            return
        }

        val triggerAtMillis = System.currentTimeMillis() + delayMillis
        val exactScheduled = TaskAlarmScheduler.scheduleExactTask(
            context = context,
            taskId = taskId,
            triggerAtMillis = triggerAtMillis
        )
        val backupDelay = if (exactScheduled) {
            delayMillis + RETRY_BACKUP_GRACE_MS
        } else {
            delayMillis
        }
        enqueueTaskWork(context, taskId, backupDelay)
    }

    fun runTaskNow(context: Context, taskId: Long) {
        val serviceStarted = TaskExecutionService.start(context, taskId)
        if (!serviceStarted) {
            enqueueTaskWork(context, taskId)
        }
    }

    fun enqueueTaskWork(context: Context, taskId: Long, delayMillis: Long = 0L) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<TelegramWorker>()
            .setInitialDelay(maxOf(0L, delayMillis), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(workDataOf(TelegramWorker.KEY_TASK_ID to taskId))
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                1,
                TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "task_$taskId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun cancelTask(context: Context, taskId: Long) {
        TaskAlarmScheduler.cancelExactTask(context, taskId)
        WorkManager.getInstance(context).cancelUniqueWork("task_$taskId")
    }

    private const val WORK_BACKUP_GRACE_MS = 10L * 60L * 1000L
    private const val RETRY_BACKUP_GRACE_MS = 2L * 60L * 1000L
}
