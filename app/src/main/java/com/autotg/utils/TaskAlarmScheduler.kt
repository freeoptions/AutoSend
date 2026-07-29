package com.autotg.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.autotg.worker.TaskReceiver
import com.autotg.worker.TelegramWorker

object TaskAlarmScheduler {
    const val ACTION_RUN_TASK = "com.autotg.action.RUN_SCHEDULED_TASK"

    private const val TAG = "TaskAlarmScheduler"
    private const val TASK_URI_PREFIX = "autotg://scheduled-task/"

    fun scheduleExactTask(
        context: Context,
        taskId: Long,
        triggerAtMillis: Long
    ): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return false
        }

        return try {
            val pendingIntent = taskPendingIntent(
                context = context,
                taskId = taskId,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ) ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "无法注册精确闹钟，已交给 WorkManager 兜底。", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "注册精确闹钟失败，已交给 WorkManager 兜底。", e)
            false
        }
    }

    fun cancelExactTask(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = taskPendingIntent(
            context = context,
            taskId = taskId,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun taskPendingIntent(
        context: Context,
        taskId: Long,
        flags: Int
    ): PendingIntent? {
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TaskReceiver::class.java).apply {
                action = ACTION_RUN_TASK
                data = Uri.parse("$TASK_URI_PREFIX$taskId")
                `package` = context.packageName
                putExtra(TelegramWorker.KEY_TASK_ID, taskId)
            },
            flags
        )
    }
}
