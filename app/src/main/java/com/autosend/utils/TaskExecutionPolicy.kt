package com.autosend.utils

import com.autosend.data.models.ScheduledTask
import com.autosend.data.models.TaskStatus

object TaskExecutionPolicy {
    const val EARLY_TRIGGER_TOLERANCE_MS = 30_000L

    fun decide(
        task: ScheduledTask,
        now: Long = System.currentTimeMillis()
    ): TaskExecutionDecision {
        if (!task.isEnabled) {
            return TaskExecutionDecision.SkipDisabled
        }

        if (task.status == TaskStatus.SUCCESS) {
            return if (!RecurringScheduleUtils.isRecurring(task)) {
                TaskExecutionDecision.SkipAlreadyCompleted
            } else {
                TaskExecutionDecision.AdvanceCompletedRecurring
            }
        }

        if (!RecurringScheduleUtils.isRecurring(task) && task.status != TaskStatus.PENDING) {
            return TaskExecutionDecision.SkipAlreadyCompleted
        }

        if (task.scheduledTime > now + EARLY_TRIGGER_TOLERANCE_MS) {
            return TaskExecutionDecision.RescheduleFuture
        }

        return TaskExecutionDecision.ExecuteNow
    }
}

enum class TaskExecutionDecision {
    ExecuteNow,
    AdvanceCompletedRecurring,
    RescheduleFuture,
    SkipAlreadyCompleted,
    SkipDisabled
}
