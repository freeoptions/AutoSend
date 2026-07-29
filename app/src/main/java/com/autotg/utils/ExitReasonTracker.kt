package com.autotg.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.autotg.data.models.LogStatus
import com.autotg.data.models.TaskLog
import com.autotg.data.repository.TelegramRepository

object ExitReasonTracker {
    private const val PREFS_NAME = "autotg_exit_reason"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val SYSTEM_TASK_ID = -999L
    private const val SYSTEM_TASK_NAME = "[\u7cfb\u7edf\u8bca\u65ad]"
    private const val SYSTEM_BOT_NAME = "AutoTG"
    private const val SYSTEM_CHAT_NAME = "\u8fdb\u7a0b\u9000\u51fa\u539f\u56e0"

    suspend fun recordLatestExitIfNeeded(context: Context, repository: TelegramRepository) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val latestExit = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            ?: return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastRecordedTimestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
        val currentTimestamp = latestExit.timestamp
        if (currentTimestamp <= 0L || currentTimestamp == lastRecordedTimestamp) return

        repository.insertLog(
            TaskLog(
                taskId = SYSTEM_TASK_ID,
                taskContent = SYSTEM_TASK_NAME,
                botName = SYSTEM_BOT_NAME,
                chatName = SYSTEM_CHAT_NAME,
                status = LogStatus.SYSTEM,
                errorMessage = buildMessage(latestExit),
                timestamp = System.currentTimeMillis()
            )
        )

        prefs.edit().putLong(KEY_LAST_TIMESTAMP, currentTimestamp).apply()
    }

    private fun buildMessage(info: ApplicationExitInfo): String {
        val reason = reasonToText(info.reason)
        val importance = importanceToText(info.importance)
        val description = info.description?.takeIf { it.isNotBlank() }
        val details = buildList {
            add("\u4e0a\u6b21\u8fdb\u7a0b\u9000\u51fa\u539f\u56e0\uff1a$reason")
            add("\u7cfb\u7edf\u91cd\u8981\u6027\uff1a$importance")
            info.processName?.takeIf { it.isNotBlank() }?.let {
                add("\u8fdb\u7a0b\u540d\uff1a$it")
            }
            if (description != null) {
                add("\u7cfb\u7edf\u63cf\u8ff0\uff1a$description")
            }
            if (info.pss > 0) {
                add("PSS\uff1a${info.pss} KB")
            }
            if (info.rss > 0) {
                add("RSS\uff1a${info.rss} KB")
            }
        }
        return details.joinToString("\uff1b")
    }

    private fun reasonToText(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR \u65e0\u54cd\u5e94"
            ApplicationExitInfo.REASON_CRASH -> "Java \u5d29\u6e83"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native \u5d29\u6e83"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "\u4f9d\u8d56\u8fdb\u7a0b\u6b7b\u4ea1"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "\u8d44\u6e90\u5360\u7528\u8fc7\u9ad8"
            ApplicationExitInfo.REASON_EXIT_SELF -> "\u5e94\u7528\u4e3b\u52a8\u9000\u51fa"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "\u521d\u59cb\u5316\u5931\u8d25"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "\u7cfb\u7edf\u4f4e\u5185\u5b58\u6e05\u7406"
            ApplicationExitInfo.REASON_OTHER -> "\u5176\u4ed6\u539f\u56e0"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "\u6743\u9650\u53d8\u66f4"
            ApplicationExitInfo.REASON_SIGNALED -> "\u6536\u5230\u7cfb\u7edf\u4fe1\u53f7\u7ec8\u6b62"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "\u7528\u6237\u6216\u7cfb\u7edf\u754c\u9762\u8bf7\u6c42\u7ed3\u675f"
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    reason == ApplicationExitInfo.REASON_USER_STOPPED
                ) {
                    "\u7528\u6237\u505c\u6b62\u524d\u53f0\u670d\u52a1\u6216\u5e94\u7528"
                } else {
                    "\u672a\u77e5\u539f\u56e0($reason)"
                }
            }
        }
    }

    private fun importanceToText(importance: Int): String {
        return when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "\u524d\u53f0"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "\u524d\u53f0\u670d\u52a1"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "\u53ef\u89c1"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "\u540e\u53f0\u670d\u52a1"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "\u7f13\u5b58\u8fdb\u7a0b"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "\u7a7a\u8fdb\u7a0b"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "\u5df2\u7ed3\u675f"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "\u53ef\u611f\u77e5"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "\u9876\u90e8\u4f11\u7720"
            else -> "\u672a\u77e5\u7ea7\u522b($importance)"
        }
    }
}
