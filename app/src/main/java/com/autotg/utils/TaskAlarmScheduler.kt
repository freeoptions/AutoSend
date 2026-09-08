package com.autotg.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.autotg.worker.TaskReceiver
import com.autotg.worker.TaskWorker

object TaskAlarmScheduler {
    const val ACTION_RUN_TASK = "com.autotg.action.RUN_SCHEDULED_TASK"

    private const val TASK_URI_PREFIX = "autotg://scheduled-task/"

    suspend fun scheduleExactTask(
        context: Context,
        taskId: Long,
        triggerAtMillis: Long
    ): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            TaskExecutionLogger.record(
                context,
                taskId,
                TaskExecutionLogger.STAGE_SCHEDULE_FAILED,
                "canScheduleExactAlarms=false；精确闹钟未提交，已保留 WorkManager 延迟补偿"
            )
            return false
        }

        return try {
            val requestCode = requestCodeForTask(taskId)
            val pendingIntent = taskPendingIntent(
                context = context,
                taskId = taskId,
                triggerAtMillis = triggerAtMillis,
                requestCode = requestCode,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ) ?: return false

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            TaskExecutionLogger.record(
                context,
                taskId,
                TaskExecutionLogger.STAGE_SCHEDULE_SUBMITTED,
                "API=setExactAndAllowWhileIdle(RTC_WAKEUP)，triggerAtMillis=$triggerAtMillis，requestCode=$requestCode"
            )
            true
        } catch (e: SecurityException) {
            TaskExecutionLogger.record(
                context,
                taskId,
                TaskExecutionLogger.STAGE_SCHEDULE_FAILED,
                "SecurityException=${e.message ?: e.javaClass.simpleName}；已保留 WorkManager 延迟补偿"
            )
            false
        } catch (e: RuntimeException) {
            TaskExecutionLogger.record(
                context,
                taskId,
                TaskExecutionLogger.STAGE_SCHEDULE_FAILED,
                "${e.javaClass.simpleName}=${e.message ?: "无详细信息"}；已保留 WorkManager 延迟补偿"
            )
            false
        }
    }

    fun cancelExactTask(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = taskPendingIntent(
            context = context,
            taskId = taskId,
            triggerAtMillis = null,
            requestCode = requestCodeForTask(taskId),
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
        triggerAtMillis: Long?,
        requestCode: Int,
        flags: Int
    ): PendingIntent? {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TaskReceiver::class.java).apply {
                action = ACTION_RUN_TASK
                data = Uri.parse("$TASK_URI_PREFIX$taskId")
                `package` = context.packageName
                putExtra(TaskWorker.KEY_TASK_ID, taskId)
                if (triggerAtMillis != null) {
                    putExtra(EXTRA_TRIGGER_AT_MILLIS, triggerAtMillis)
                }
            },
            flags
        )
    }

    internal fun requestCodeForTask(taskId: Long): Int {
        // requestCode 对同一任务稳定；data URI 同时参与 PendingIntent 身份比较，
        // 即使极端情况下 64 位 ID 折叠后相同，也不会互相覆盖。
        val folded = taskId xor (taskId ushr 32)
        return TASK_REQUEST_CODE_BASE + (folded.toInt() and TASK_REQUEST_CODE_MASK)
    }

    const val EXTRA_TRIGGER_AT_MILLIS = "triggerAtMillis"

    private const val TASK_REQUEST_CODE_BASE = 10_000
    private const val TASK_REQUEST_CODE_MASK = 0x3FFF_FFFF
}
