package com.autotg.utils

import com.autotg.data.models.ScheduledTask
import java.util.Calendar

object RecurringScheduleUtils {
    fun isRecurring(
        cronExpression: String?,
        lunarMonth: Int?,
        lunarDay: Int?
    ): Boolean {
        return !cronExpression.isNullOrBlank() ||
            LunarCalendarUtils.isValidDate(lunarMonth, lunarDay)
    }

    fun isRecurring(task: ScheduledTask): Boolean {
        return isRecurring(task.cronExpression, task.lunarMonth, task.lunarDay)
    }

    fun getNextExecutionTimes(
        task: ScheduledTask,
        fromTime: Long = System.currentTimeMillis(),
        count: Int = 10
    ): List<Long> {
        if (task.isLunarRecurring) {
            val time = Calendar.getInstance().apply { timeInMillis = task.scheduledTime }
            return LunarCalendarUtils.getNextExecutionTimes(
                month = task.lunarMonth!!,
                day = task.lunarDay!!,
                hour = time.get(Calendar.HOUR_OF_DAY),
                minute = time.get(Calendar.MINUTE),
                leapMonth = task.lunarLeapMonth,
                fromTime = fromTime,
                count = count
            )
        }

        val cron = task.cronExpression?.takeIf { it.isNotBlank() } ?: return emptyList()
        val results = mutableListOf<Long>()
        var cursor = fromTime
        while (results.size < count) {
            val next = CronUtils.getNextExecutionTimeFrom(cron, cursor + 1_000L) ?: break
            results += next
            cursor = next
        }
        return results
    }

    fun getNextExecutionTimeFrom(
        task: ScheduledTask,
        fromTime: Long = System.currentTimeMillis()
    ): Long? {
        if (task.isLunarRecurring) {
            val time = Calendar.getInstance().apply { timeInMillis = task.scheduledTime }
            return LunarCalendarUtils.getNextExecutionTime(
                month = task.lunarMonth!!,
                day = task.lunarDay!!,
                hour = time.get(Calendar.HOUR_OF_DAY),
                minute = time.get(Calendar.MINUTE),
                leapMonth = task.lunarLeapMonth,
                fromTime = fromTime
            )
        }

        val cron = task.cronExpression?.takeIf { it.isNotBlank() } ?: return null
        return CronUtils.getNextExecutionTimeFrom(cron, fromTime)
    }
}
