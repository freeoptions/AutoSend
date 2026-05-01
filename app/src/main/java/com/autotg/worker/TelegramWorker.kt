package com.autotg.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autotg.data.models.TaskStatus
import com.autotg.data.repository.TelegramRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TelegramWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TelegramRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId == -1L) return Result.failure()

        val result = repository.sendScheduledMessage(taskId)

        return if (result.isSuccess) {
            Result.success()
        } else {
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                // Final failure, mark task as FAILED
                repository.getTaskById(taskId)?.let { task ->
                    repository.updateTask(task.copy(status = TaskStatus.FAILED))
                }
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_TASK_ID = "taskId"
    }
}
