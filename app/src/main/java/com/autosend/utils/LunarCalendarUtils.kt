package com.autosend.utils

import android.icu.util.Calendar as IcuCalendar
import android.icu.util.ChineseCalendar
import java.time.LocalDate
import java.util.Calendar

/**
 * 农历日期计算。1901—2100 年使用香港天文台历表，避免不同 Android ICU 版本的推算误差。
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

    private val monthNames = listOf("正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月")
    private val dayNames = listOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )

    fun monthName(month: Int): String = monthNames.getOrNull(month - 1).orEmpty()

    fun dayName(day: Int): String = dayNames.getOrNull(day - 1).orEmpty()

    fun formatDate(date: LunarDate): String =
        "${if (date.isLeapMonth) "闰" else ""}${monthName(date.month)}${dayName(date.day)}"

    fun isValidDate(month: Int?, day: Int?): Boolean {
        return month != null && day != null &&
            month in MIN_MONTH..MAX_MONTH && day in MIN_DAY..MAX_DAY
    }

    fun getLunarDate(timeInMillis: Long = System.currentTimeMillis()): LunarDate {
        val local = Calendar.getInstance().apply { this.timeInMillis = timeInMillis }
        val date = LocalDate.of(
            local.get(Calendar.YEAR), local.get(Calendar.MONTH) + 1, local.get(Calendar.DAY_OF_MONTH)
        )
        HkoLunarCalendar.getDate(date.toEpochDay())?.let { return it }
        // 历表范围外保留原有能力；不将系统算法用于已知有误差的范围。
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
