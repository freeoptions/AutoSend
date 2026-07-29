package com.autotg.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autotg.utils.TaskSendExecutor
import com.autotg.utils.TaskSendOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TelegramWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taskSendExecutor: TaskSendExecutor
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId == -1L) return Result.failure()

        return when (taskSendExecutor.execute(taskId)) {
            TaskSendOutcome.FINAL_FAILURE -> Result.failure()
            TaskSendOutcome.SENT,
            TaskSendOutcome.RETRY_SCHEDULED,
            TaskSendOutcome.RESCHEDULED,
            TaskSendOutcome.SKIPPED -> Result.success()
        }
    }

    companion object {
        const val KEY_TASK_ID = "taskId"
    }
}
