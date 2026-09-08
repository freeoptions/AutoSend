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
import com.autotg.worker.TaskWorker
import java.util.concurrent.TimeUnit

object WorkManagerHelper {
    suspend fun scheduleTask(context: Context, task: ScheduledTask): Boolean {
        val now = System.currentTimeMillis()
        val delay = task.scheduledTime - now
        if (!task.isEnabled) {
            cancelTask(context, task.id)
            return false
        }

        if (delay <= 0 && !RecurringScheduleUtils.isRecurring(task)) {
            runTaskNow(context, task.id)
            return false
        }

        if (delay <= 0L) {
            runTaskNow(context, task.id)
            return false
        }

        val exactScheduled = TaskAlarmScheduler.scheduleExactTask(
            context = context,
            taskId = task.id,
            triggerAtMillis = task.scheduledTime
        )
        RecoveryScheduler.ensureRecoveryWork(context)
        RecoveryScheduler.scheduleTaskPreflight(context, task.scheduledTime)
        val backupDelay = if (exactScheduled) delay + WORK_BACKUP_GRACE_MS else delay
        enqueueTaskWork(
            context = context,
            taskId = task.id,
            triggerAtMillis = task.scheduledTime,
            delayMillis = backupDelay
        )
        return exactScheduled
    }

    suspend fun scheduleRetry(context: Context, taskId: Long, delayMillis: Long) {
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
        enqueueTaskWork(
            context = context,
            taskId = taskId,
            triggerAtMillis = triggerAtMillis,
            delayMillis = backupDelay
        )
    }

    fun runTaskNow(context: Context, taskId: Long) {
        val serviceStart = TaskExecutionService.start(
            context = context,
            taskId = taskId,
            triggerAtMillis = System.currentTimeMillis()
        )
        if (serviceStart.isFailure) {
            enqueueTaskWork(context, taskId, System.currentTimeMillis())
        }
    }

    fun enqueueTaskWork(
        context: Context,
        taskId: Long,
        triggerAtMillis: Long,
        delayMillis: Long = 0L,
        forceExecution: Boolean = false
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<TaskWorker>()
            .setInitialDelay(maxOf(0L, delayMillis), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    TaskWorker.KEY_TASK_ID to taskId,
                    TaskAlarmScheduler.EXTRA_TRIGGER_AT_MILLIS to triggerAtMillis,
                    TaskWorker.KEY_FORCE_EXECUTION to forceExecution
                )
            )
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

    fun enqueueRecurringRetry(
        context: Context,
        taskId: Long,
        occurrenceAtMillis: Long,
        delayMillis: Long
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<TaskWorker>()
            .setInitialDelay(maxOf(0L, delayMillis), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    TaskWorker.KEY_TASK_ID to taskId,
                    TaskAlarmScheduler.EXTRA_TRIGGER_AT_MILLIS to occurrenceAtMillis,
                    TaskWorker.KEY_FORCE_EXECUTION to true
                )
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "task_retry_${taskId}_$occurrenceAtMillis",
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
