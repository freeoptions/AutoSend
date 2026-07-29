package com.autotg.utils

import java.util.*

/**
 * A simplified Cron utility to parse cron expressions and calculate next execution times.
 * Format: [second] [minute] [hour] [dayOfMonth] [month] [dayOfWeek]
 * Support: *, /, -, ,
 */
object CronUtils {

    fun getNextExecutionTimes(cron: String, count: Int = 10): List<Long> {
        val results = mutableListOf<Long>()
        var nextTime = System.currentTimeMillis()

        try {
            repeat(count) {
                val next = calculateNext(cron, nextTime + 1000) // Start from 1s later to avoid current time
                if (next != null) {
                    results.add(next)
                    nextTime = next
                }
            }
        } catch (e: Exception) {
            // Invalid cron
        }
        return results
    }

    fun getNextExecutionTimeFrom(cron: String, fromTime: Long): Long? {
        return runCatching { calculateNext(cron, fromTime) }.getOrNull()
    }

    private fun calculateNext(cron: String, fromTime: Long): Long? {
        val calendar = Calendar.getInstance().apply { timeInMillis = fromTime }
        val parts = cron.trim().split(Regex("\\s+"))
        if (parts.size < 5) return null // At least min, hour, dom, mon, dow

        // Handle seconds if present, else default to 0
        val allParts = if (parts.size == 6) {
            parts
        } else {
            listOf("0") + parts
        }
        val secExp = allParts[0]
        val minExp = allParts[1]
        val hourExp = allParts[2]
        val domExp = allParts[3]
        val monExp = allParts[4]
        val dowExp = allParts[5]

        // We use a simple brute-force approach for correctness in complex cases
        // Limit to 2 years ahead to prevent infinite loops
        val limit = Calendar.getInstance().apply {
            timeInMillis = fromTime
            add(Calendar.YEAR, 2)
        }.timeInMillis

        while (calendar.timeInMillis < limit) {
            if (!match(calendar.get(Calendar.MONTH) + 1, monExp)) {
                calendar.add(Calendar.MONTH, 1)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                continue
            }
            if (!matchDayOfMonth(calendar, domExp) || !matchDayOfWeek(calendar, dowExp)) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                continue
            }
            if (!match(calendar.get(Calendar.HOUR_OF_DAY), hourExp)) {
                calendar.add(Calendar.HOUR_OF_DAY, 1)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                continue
            }
            if (!match(calendar.get(Calendar.MINUTE), minExp)) {
                calendar.add(Calendar.MINUTE, 1)
                calendar.set(Calendar.SECOND, 0)
                continue
            }
            if (!match(calendar.get(Calendar.SECOND), secExp)) {
                calendar.add(Calendar.SECOND, 1)
                continue
            }

            return calendar.timeInMillis
        }

        return null
    }

    private fun matchDayOfMonth(calendar: Calendar, expression: String): Boolean {
        if (expression == "L") {
            val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            return calendar.get(Calendar.DAY_OF_MONTH) == lastDay
        }
        return match(calendar.get(Calendar.DAY_OF_MONTH), expression)
    }

    private fun match(value: Int, expression: String): Boolean {
        if (expression == "*" || expression == "?") return true
        if (expression.contains(",")) {
            return expression.split(",").any { match(value, it) }
        }
        if (expression.contains("/")) {
            val parts = expression.split("/")
            val start = if (parts[0] == "*") 0 else parts[0].toInt()
            val interval = parts[1].toInt()
            return value >= start && (value - start) % interval == 0
        }
        if (expression.contains("-")) {
            val parts = expression.split("-")
            return value in parts[0].toInt()..parts[1].toInt()
        }
        return value == expression.toInt()
    }

    private fun matchDayOfWeek(calendar: Calendar, expression: String): Boolean {
        if (expression == "*" || expression == "?") return true
        // Calendar.DAY_OF_WEEK: Sun=1, Mon=2... Sat=7
        // Cron standard: Sun=0 or 7, Mon=1...
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        val cronDow = if (dow == 1) 0 else dow - 1 // Convert to 0-6 (Sun-Sat)

        return match(cronDow, expression) || (cronDow == 0 && expression == "7")
    }
}
