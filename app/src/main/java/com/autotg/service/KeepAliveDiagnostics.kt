package com.autotg.service

import android.content.Context

data class KeepAliveDiagnosticsSnapshot(
    val lastServiceStartedAt: Long,
    val lastHeartbeatAt: Long,
    val lastRecoveryAt: Long,
    val nextRecoveryAt: Long,
    val lastStartError: String?
)

object KeepAliveDiagnostics {
    private const val PREFS_NAME = "autotg_keep_alive_diagnostics"
    private const val KEY_LAST_SERVICE_STARTED_AT = "last_service_started_at"
    private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
    private const val KEY_LAST_RECOVERY_AT = "last_recovery_at"
    private const val KEY_NEXT_RECOVERY_AT = "next_recovery_at"
    private const val KEY_LAST_START_ERROR = "last_start_error"

    fun recordServiceStarted(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_SERVICE_STARTED_AT, System.currentTimeMillis())
            .remove(KEY_LAST_START_ERROR)
            .apply()
    }

    fun recordHeartbeat(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordRecoveryTriggered(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_RECOVERY_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordNextRecovery(context: Context, triggerAtMillis: Long) {
        prefs(context).edit()
            .putLong(KEY_NEXT_RECOVERY_AT, triggerAtMillis)
            .apply()
    }

    fun clearNextRecovery(context: Context) {
        prefs(context).edit().remove(KEY_NEXT_RECOVERY_AT).apply()
    }

    fun recordStartFailure(context: Context, throwable: Throwable) {
        val message = throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable.javaClass.simpleName
        prefs(context).edit().putString(KEY_LAST_START_ERROR, message).apply()
    }

    fun snapshot(context: Context): KeepAliveDiagnosticsSnapshot {
        val prefs = prefs(context)
        return KeepAliveDiagnosticsSnapshot(
            lastServiceStartedAt = prefs.getLong(KEY_LAST_SERVICE_STARTED_AT, 0L),
            lastHeartbeatAt = prefs.getLong(KEY_LAST_HEARTBEAT_AT, 0L),
            lastRecoveryAt = prefs.getLong(KEY_LAST_RECOVERY_AT, 0L),
            nextRecoveryAt = prefs.getLong(KEY_NEXT_RECOVERY_AT, 0L),
            lastStartError = prefs.getString(KEY_LAST_START_ERROR, null)
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
