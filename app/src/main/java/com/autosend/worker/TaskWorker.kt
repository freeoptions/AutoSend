package com.autosend.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autosend.utils.TaskAlarmScheduler
import com.autosend.utils.TaskSendExecutor
import com.autosend.utils.TaskSendOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taskSendExecutor: TaskSendExecutor
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId == -1L) return Result.failure()
        val triggerAtMillis = inputData.getLong(
            TaskAlarmScheduler.EXTRA_TRIGGER_AT_MILLIS,
            0L
        )
        val forceExecution = inputData.getBoolean(KEY_FORCE_EXECUTION, false)

        return when (
            taskSendExecutor.execute(
                taskId = taskId,
                triggerAtMillis = triggerAtMillis,
                forceExecution = forceExecution
            )
        ) {
            TaskSendOutcome.FINAL_FAILURE -> Result.failure()
            TaskSendOutcome.SENT,
            TaskSendOutcome.RETRY_SCHEDULED,
            TaskSendOutcome.RESCHEDULED,
            TaskSendOutcome.SKIPPED -> Result.success()
        }
    }

    companion object {
        const val KEY_TASK_ID = "taskId"
        const val KEY_FORCE_EXECUTION = "forceExecution"
    }
}
