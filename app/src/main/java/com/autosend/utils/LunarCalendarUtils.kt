package com.autosend.utils

import android.icu.util.Calendar as IcuCalendar
import android.icu.util.ChineseCalendar
import java.util.Calendar

/**
 * 农历日期计算。Android API 24 起自带 ChineseCalendar，设备无需额外下载农历库。
 * 这里按设备当前时区的本地日期寻找下一次触发时间，适合生日等每年重复的提醒。
 */
data class LunarDate(
    val month: Int,
    val day: Int,
    val isLeapMonth: Boolean
)

object LunarCalendarUtils {
    const val MIN_MONTH = 1
    const val MAX_MONTH = 12
    const val MIN_DAY = 1
    const val MAX_DAY = 30

    private const val SEARCH_YEARS = 5

    fun isValidDate(month: Int?, day: Int?): Boolean {
        return month != null && day != null &&
            month in MIN_MONTH..MAX_MONTH && day in MIN_DAY..MAX_DAY
    }

    fun getLunarDate(timeInMillis: Long = System.currentTimeMillis()): LunarDate {
        val calendar = ChineseCalendar().apply { this.timeInMillis = timeInMillis }
        return LunarDate(
            month = calendar.get(IcuCalendar.MONTH) + 1,
            day = calendar.get(IcuCalendar.DAY_OF_MONTH),
            isLeapMonth = calendar.get(IcuCalendar.IS_LEAP_MONTH) != 0
        )
    }

    fun isLunarDate(
        timeInMillis: Long,
        month: Int,
        day: Int,
        leapMonth: Boolean = false
    ): Boolean {
        val lunarDate = getLunarDate(timeInMillis)
        return lunarDate.month == month &&
            lunarDate.day == day &&
            lunarDate.isLeapMonth == leapMonth
    }

    fun getNextExecutionTimes(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        leapMonth: Boolean = false,
        fromTime: Long = System.currentTimeMillis(),
        count: Int = 10
    ): List<Long> {
        if (count <= 0 || !isValidDate(month, day) || hour !in 0..23 || minute !in 0..59) {
            return emptyList()
        }
        return searchExecutionTimes(
            month = month,
            day = day,
            hour = hour,
            minute = minute,
            leapMonth = leapMonth,
            fromTime = fromTime,
            count = count
        )
    }

    fun getNextExecutionTime(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        leapMonth: Boolean = false,
        fromTime: Long = System.currentTimeMillis()
    ): Long? {
        return getNextExecutionTimes(
            month = month,
            day = day,
            hour = hour,
            minute = minute,
            leapMonth = leapMonth,
            fromTime = fromTime,
            count = 1
        ).firstOrNull()
    }

    private fun searchExecutionTimes(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        leapMonth: Boolean,
        fromTime: Long,
        count: Int
    ): List<Long> {
        val results = mutableListOf<Long>()

        val candidateDay = Calendar.getInstance().apply {
            timeInMillis = fromTime
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val searchLimit = (candidateDay.clone() as Calendar).apply {
            add(Calendar.YEAR, SEARCH_YEARS)
        }

        while (candidateDay.before(searchLimit) && results.size < count) {
            val candidate = (candidateDay.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }
            if (
                candidate.timeInMillis > fromTime &&
                isLunarDate(
                    timeInMillis = candidate.timeInMillis,
                    month = month,
                    day = day,
                    leapMonth = leapMonth
                )
            ) {
                results += candidate.timeInMillis
            }
            candidateDay.add(Calendar.DAY_OF_MONTH, 1)
        }
        return results
    }
}
