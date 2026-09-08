package com.autotg.utils

import com.autotg.data.models.ScheduledTask
import com.autotg.data.models.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskExecutionPolicyTest {

    @Test
    fun completedOneTimeTaskIsSkippedToAvoidDuplicateSend() {
        val now = 1_000_000L
        val task = testTask(
            scheduledTime = now - 1_000L,
            status = TaskStatus.SUCCESS,
            cronExpression = null
        )

        val decision = TaskExecutionPolicy.decide(task, now)

        assertEquals(TaskExecutionDecision.SkipAlreadyCompleted, decision)
    }

    @Test
    fun completedRecurringTaskAdvancesScheduleInsteadOfDuplicateSend() {
        val now = 1_000_000L
        val task = testTask(
            scheduledTime = now - 1_000L,
            status = TaskStatus.SUCCESS,
            cronExpression = "0 0 * * *"
        )

        val decision = TaskExecutionPolicy.decide(task, now)

        assertEquals(TaskExecutionDecision.AdvanceCompletedRecurring, decision)
    }

    @Test
    fun completedLunarBirthdayAdvancesScheduleInsteadOfDuplicateSend() {
        val now = 1_000_000L
        val task = testTask(
            scheduledTime = now - 1_000L,
            status = TaskStatus.SUCCESS,
            lunarMonth = 1,
            lunarDay = 1
        )

        val decision = TaskExecutionPolicy.decide(task, now)

        assertEquals(TaskExecutionDecision.AdvanceCompletedRecurring, decision)
    }

    @Test
    fun futureTaskIsRescheduledInsteadOfSentEarly() {
        val now = 1_000_000L
        val task = testTask(
            scheduledTime = now + TaskExecutionPolicy.EARLY_TRIGGER_TOLERANCE_MS + 1_000L,
            status = TaskStatus.PENDING
        )

        val decision = TaskExecutionPolicy.decide(task, now)

        assertEquals(TaskExecutionDecision.RescheduleFuture, decision)
    }

    @Test
    fun duePendingTaskExecutes() {
        val now = 1_000_000L
        val task = testTask(
            scheduledTime = now - 1_000L,
            status = TaskStatus.PENDING
        )

        val decision = TaskExecutionPolicy.decide(task, now)

        assertEquals(TaskExecutionDecision.ExecuteNow, decision)
    }

    @Test
    fun alarmRequestCodeIsStableAndSeparatedForNormalTaskIds() {
        assertEquals(
            TaskAlarmScheduler.requestCodeForTask(42L),
            TaskAlarmScheduler.requestCodeForTask(42L)
        )
        assertNotEquals(
            TaskAlarmScheduler.requestCodeForTask(42L),
            TaskAlarmScheduler.requestCodeForTask(43L)
        )
    }

    private fun testTask(
        scheduledTime: Long,
        status: TaskStatus = TaskStatus.PENDING,
        cronExpression: String? = null,
        lunarMonth: Int? = null,
        lunarDay: Int? = null
    ): ScheduledTask {
        return ScheduledTask(
            id = 42L,
            botId = 1L,
            chatId = 1L,
            content = "测试消息",
            scheduledTime = scheduledTime,
            status = status,
            cronExpression = cronExpression,
            lunarMonth = lunarMonth,
            lunarDay = lunarDay
        )
    }
}
