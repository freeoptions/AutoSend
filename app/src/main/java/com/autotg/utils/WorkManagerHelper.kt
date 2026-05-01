package com.autotg.utils

import android.content.Context
import androidx.work.*
import com.autotg.data.models.ScheduledTask
import com.autotg.worker.TelegramWorker
import java.util.concurrent.TimeUnit

object WorkManagerHelper {

    fun scheduleTask(context: Context, task: ScheduledTask) {
        val delay = task.scheduledTime - System.currentTimeMillis()
        val workRequest = OneTimeWorkRequestBuilder<TelegramWorker>()
            .setInitialDelay(maxOf(0, delay), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(TelegramWorker.KEY_TASK_ID to task.id))
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "task_${task.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun cancelTask(context: Context, taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("task_$taskId")
    }
}
