"""离线检查实际阴历工具和 Kotlin 语法；不执行 Gradle、不启动 Android、不联网。

可选参数为天文台逐日期望 TSV（公历\t阴历月\t日\t是否闰月）。
ICU 范围外回退使用会抛错的占位类，不代表 Android ICU 或真实界面验证。
"""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from check_telegram_api import SOURCE, jar
from check_telegram_queue import check_kotlin_syntax

ICU = """
package android.icu.util
open class Calendar {
    companion object { const val MONTH = 2; const val DAY_OF_MONTH = 5; const val IS_LEAP_MONTH = 22 }
}
class ChineseCalendar : Calendar() {
    var timeInMillis: Long = 0
    fun get(@Suppress("UNUSED_PARAMETER") field: Int): Int = error("Unexpected ICU fallback in table range")
}
"""

CHECK = r'''
package com.autosend.utils

import java.io.File
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone

private fun time(value: String, hour: Int = 0, minute: Int = 0): Long {
    val date = LocalDate.parse(value)
    return Calendar.getInstance().apply {
        clear()
        set(date.year, date.monthValue - 1, date.dayOfMonth, hour, minute, 0)
    }.timeInMillis
}

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    for ((solar, expected) in listOf(
        "1901-01-01" to LunarDate(11, 11, false),
        "2026-02-17" to LunarDate(1, 1, false),
        "2027-02-05" to LunarDate(12, 29, false),
        "2027-02-06" to LunarDate(1, 1, false),
        "2027-02-11" to LunarDate(1, 6, false),
        "2027-02-12" to LunarDate(1, 7, false),
        "2027-02-13" to LunarDate(1, 8, false),
        "2025-07-24" to LunarDate(6, 30, false),
        "2025-07-25" to LunarDate(6, 1, true),
        "2033-12-22" to LunarDate(11, 1, true),
        "2100-12-31" to LunarDate(12, 1, false)
    )) {
        for (hour in listOf(0, 12, 23)) {
            check(LunarCalendarUtils.getLunarDate(time(solar, hour)) == expected) { "$solar $hour" }
        }
    }
    check(HkoLunarCalendar.getDate(LocalDate.parse("1900-12-31").toEpochDay()) == null)
    check(HkoLunarCalendar.getDate(LocalDate.parse("2101-01-01").toEpochDay()) == null)

    val from = time("2026-10-17")
    check(LunarCalendarUtils.getNextExecutionTime(1, 6, 0, 0, fromTime = from) == time("2027-02-11"))
    check(LunarCalendarUtils.getNextExecutionTime(1, 6, 9, 30, fromTime = from) == time("2027-02-11", 9, 30))
    check(LunarCalendarUtils.getNextExecutionTime(1, 6, 0, 0, fromTime = time("2027-02-11")) == time("2028-01-31"))
    check(LunarCalendarUtils.getNextExecutionTime(1, 6, 0, 0, fromTime = time("2027-02-10", 23, 59)) == time("2027-02-11"))
    check(LunarCalendarUtils.getNextExecutionTime(12, 30, 0, 0, fromTime = from) == time("2030-02-02"))
    check(LunarCalendarUtils.getNextExecutionTime(6, 1, 0, 0, leapMonth = true,
        fromTime = time("2025-01-01")) == time("2025-07-25"))
    val many = LunarCalendarUtils.getNextExecutionTimes(1, 6, 9, 30, fromTime = from, count = 5)
    check(many.size == 5 && many.zipWithNext().all { (a, b) -> a < b })
    check(many.all { LunarCalendarUtils.isLunarDate(it, 1, 6) })
    check(LunarCalendarUtils.getNextExecutionTime(0, 6, 0, 0, fromTime = from) == null)
    check(LunarCalendarUtils.getNextExecutionTime(1, 31, 0, 0, fromTime = from) == null)
    check(LunarCalendarUtils.getNextExecutionTime(1, 6, 24, 0, fromTime = from) == null)
    check(LunarCalendarUtils.formatDate(LunarDate(1, 6, false)) == "正月初六")
    check(LunarCalendarUtils.formatDate(LunarDate(6, 21, true)) == "闰六月廿一")
    check(LunarCalendarUtils.monthName(11) == "冬月" && LunarCalendarUtils.monthName(12) == "腊月")
    check((1..30).map { LunarCalendarUtils.dayName(it) }.distinct().size == 30)
    // 与设备本地公历日对齐，跨时区仍按同一个本地日期处理。
    for (zone in listOf("UTC", "America/Los_Angeles", "Pacific/Kiritimati")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        check(LunarCalendarUtils.getLunarDate(time("2027-02-11")) == LunarDate(1, 6, false))
        check(LunarCalendarUtils.getNextExecutionTime(1, 6, 0, 0, fromTime = time("2026-10-17")) == time("2027-02-11"))
    }
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    println("Lunar dates, midnight, recurrence boundaries, leap months, short months, names and local time zones passed")
    if (args.isNotEmpty()) {
        var checked = 0
        File(args.single()).forEachLine { line ->
            val fields = line.split('\t')
            val expected = LunarDate(fields[1].toInt(), fields[2].toInt(), fields[3].toBoolean())
            check(LunarCalendarUtils.getLunarDate(time(fields[0])) == expected) { line }
            checked++
        }
        check(checked == 73049)
        println("All $checked official HKO daily conversions passed (1901-2100)")
    }
}
'''


def main():
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.23"),
                jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
                jar("org.jetbrains.kotlin", "kotlin-script-runtime", "1.9.23"),
                jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                jar("org.jetbrains", "annotations", "13.0")]
    stdlib = compiler[1]
    with tempfile.TemporaryDirectory(prefix="autosend-lunar-check-") as directory:
        temp = Path(directory)
        (temp / "Icu.kt").write_text(ICU, encoding="utf-8")
        (temp / "Check.kt").write_text(CHECK, encoding="utf-8")
        sources = [SOURCE / "utils/HkoLunarCalendar.kt", SOURCE / "utils/LunarCalendarUtils.kt",
                   temp / "Icu.kt", temp / "Check.kt"]
        java = shutil.which("java")
        subprocess.run([java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-classpath", stdlib, "-d", str(temp / "classes"),
                        *map(str, sources)], check=True)
        subprocess.run([java, "-cp", os.pathsep.join([str(temp / "classes"), stdlib]),
                        "com.autosend.utils.CheckKt", *sys.argv[1:]], check=True)
    check_kotlin_syntax()


if __name__ == "__main__":
    main()
