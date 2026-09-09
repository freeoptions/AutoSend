package com.autosend.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autosend.worker.RecoveryCheckReceiver

data class RecoveryScheduleSnapshot(
    val nextDailyRecoveryAt: Long,
    val nextTaskPreflightAt: Long
)

object RecoveryScheduler {
    const val ACTION_RUN_RECOVERY_CHECK = "com.autosend.action.RUN_RECOVERY_CHECK"
    const val EXTRA_RECOVERY_KIND = "recovery_kind"

    private const val DAILY_RECOVERY_REQUEST_CODE = 7002
    private const val PREFLIGHT_RECOVERY_REQUEST_CODE = 7003
    private const val LOW_FREQUENCY_RECOVERY_REPEAT_MS = 24L * 60L * 60L * 1000L
    private const val TASK_PREFLIGHT_LEAD_MS = 30L * 60L * 1000L
    private const val PREFLIGHT_REARM_GUARD_MS = 60_000L
    private const val SCHEDULE_TOLERANCE_MS = 5L * 60L * 1000L
    private const val PREFS_NAME = "autosend_recovery_scheduler"
    private const val KEY_NEXT_DAILY_RECOVERY_AT = "next_daily_recovery_at"
    private const val KEY_NEXT_PREFLIGHT_AT = "next_preflight_at"
    private const val LEGACY_KEY_NEXT_RECOVERY_AT = "next_recovery_at"
    private const val RECOVERY_KIND_DAILY = "daily"
    private const val RECOVERY_KIND_PREFLIGHT = "preflight"
    private const val TAG = "RecoveryScheduler"

    fun ensureRecoveryWork(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val scheduledAt = prefs.getLong(KEY_NEXT_DAILY_RECOVERY_AT, 0L)
        val latestAcceptableAt = now + LOW_FREQUENCY_RECOVERY_REPEAT_MS + SCHEDULE_TOLERANCE_MS
        if (scheduledAt in (now + 1)..latestAcceptableAt) return
        scheduleDailyRecovery(context, now + LOW_FREQUENCY_RECOVERY_REPEAT_MS)
    }

    fun forceRebuildRecoveryWork(context: Context) {
        scheduleDailyRecovery(
            context,
            System.currentTimeMillis() + LOW_FREQUENCY_RECOVERY_REPEAT_MS
        )
    }

    private fun scheduleRecoveryAlarm(
        context: Context,
        triggerAtMillis: Long,
        requestCode: Int,
        recoveryKind: String,
        preferenceKey: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, RecoveryCheckReceiver::class.java).apply {
                action = ACTION_RUN_RECOVERY_CHECK
                `package` = context.packageName
                putExtra(EXTRA_RECOVERY_KIND, recoveryKind)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            // 恢复检查允许延迟，不占用精确闹钟；准点主链路只在 TaskAlarmScheduler 中。
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(preferenceKey, triggerAtMillis)
                .remove(LEGACY_KEY_NEXT_RECOVERY_AT)
                .apply()
        } catch (e: RuntimeException) {
            Log.w(TAG, "无法注册进程外恢复检查。", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(preferenceKey)
                .apply()
        }
    }

    fun scheduleNextRecoveryCheckFromNow(context: Context) {
        scheduleDailyRecovery(
            context,
            System.currentTimeMillis() + LOW_FREQUENCY_RECOVERY_REPEAT_MS
        )
    }

    fun scheduleTaskPreflight(context: Context, taskTimeMillis: Long) {
        if (taskTimeMillis <= 0L) return

        val now = System.currentTimeMillis()
        if (taskTimeMillis - now <= TASK_PREFLIGHT_LEAD_MS + PREFLIGHT_REARM_GUARD_MS) return

        val preflightAt = taskTimeMillis - TASK_PREFLIGHT_LEAD_MS
        val scheduledAt = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_NEXT_PREFLIGHT_AT, 0L)

        // 已有更早的任务预恢复时，不用被更晚的任务推迟。
        if (scheduledAt in (now + 1)..preflightAt) return
        scheduleRecoveryAlarm(
            context = context,
            triggerAtMillis = preflightAt,
            requestCode = PREFLIGHT_RECOVERY_REQUEST_CODE,
            recoveryKind = RECOVERY_KIND_PREFLIGHT,
            preferenceKey = KEY_NEXT_PREFLIGHT_AT
        )
    }

    fun onRecoveryTriggered(context: Context, recoveryKind: String?) {
        if (recoveryKind == RECOVERY_KIND_PREFLIGHT) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_PREFLIGHT_AT)
                .apply()
            ensureRecoveryWork(context)
        } else {
            scheduleNextRecoveryCheckFromNow(context)
        }
    }

    fun scheduleSnapshot(context: Context): RecoveryScheduleSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RecoveryScheduleSnapshot(
            nextDailyRecoveryAt = prefs.getLong(KEY_NEXT_DAILY_RECOVERY_AT, 0L),
            nextTaskPreflightAt = prefs.getLong(KEY_NEXT_PREFLIGHT_AT, 0L)
        )
    }

    fun cancelRecoveryCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(
            DAILY_RECOVERY_REQUEST_CODE to RECOVERY_KIND_DAILY,
            PREFLIGHT_RECOVERY_REQUEST_CODE to RECOVERY_KIND_PREFLIGHT
        ).forEach { (requestCode, recoveryKind) ->
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, RecoveryCheckReceiver::class.java).apply {
                    action = ACTION_RUN_RECOVERY_CHECK
                    `package` = context.packageName
                    putExtra(EXTRA_RECOVERY_KIND, recoveryKind)
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_NEXT_DAILY_RECOVERY_AT)
            .remove(KEY_NEXT_PREFLIGHT_AT)
            .remove(LEGACY_KEY_NEXT_RECOVERY_AT)
            .apply()
    }

    private fun scheduleDailyRecovery(context: Context, triggerAtMillis: Long) {
        scheduleRecoveryAlarm(
            context = context,
            triggerAtMillis = triggerAtMillis,
            requestCode = DAILY_RECOVERY_REQUEST_CODE,
            recoveryKind = RECOVERY_KIND_DAILY,
            preferenceKey = KEY_NEXT_DAILY_RECOVERY_AT
        )
    }

}
