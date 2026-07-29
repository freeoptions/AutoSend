package com.autotg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autotg.MainActivity
import com.autotg.R
import com.autotg.data.repository.TelegramRepository
import com.autotg.utils.ExitReasonTracker
import com.autotg.utils.RecoveryScheduler
import com.autotg.utils.SchedulerRecovery
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class KeepAliveService : Service() {

    @Inject
    lateinit var repository: TelegramRepository

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var watchdogJob: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        manuallyStopped = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val shouldKeepRunning = isEnabled(applicationContext)

        manuallyStopped = false
        RecoveryScheduler.cancelKeepAliveRestart(applicationContext)
        RecoveryScheduler.ensureRecoveryWork(applicationContext)
        if (!foregroundStarted) {
            val startResult = runCatching { startForegroundCompat() }
            if (startResult.isFailure) {
                isRunning = false
                KeepAliveDiagnostics.recordStartFailure(
                    applicationContext,
                    startResult.exceptionOrNull() ?: IllegalStateException("前台服务启动失败")
                )
                stopSelf(startId)
                return START_NOT_STICKY
            }
            foregroundStarted = true
            isRunning = true
            KeepAliveDiagnostics.recordServiceStarted(applicationContext)
        }

        if (shouldKeepRunning) {
            startWatchdog()
        } else {
            stopSelf(startId)
        }

        return if (shouldKeepRunning) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestartIfNeeded()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        watchdogJob?.cancel()
        serviceJob.cancel()
        scheduleRestartIfNeeded()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return

        watchdogJob = serviceScope.launch {
            runWatchdogOnce()
        }
    }

    private suspend fun runWatchdogOnce() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:watchdog"
        ).apply {
            setReferenceCounted(false)
            acquire(WATCHDOG_WAKE_LOCK_TIMEOUT_MS)
        }
        try {
            runCatching {
                RecoveryScheduler.ensureRecoveryWork(applicationContext)
                ExitReasonTracker.recordLatestExitIfNeeded(applicationContext, repository)
                SchedulerRecovery.recoverEnabledTasks(applicationContext, repository)
                KeepAliveDiagnostics.recordHeartbeat(applicationContext)
            }.onFailure {
                Log.w(TAG, "后台守护检查失败。", it)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun scheduleRestartIfNeeded() {
        if (!manuallyStopped && isEnabled(applicationContext)) {
            RecoveryScheduler.scheduleKeepAliveRestart(applicationContext)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_telegram)
            .setContentTitle("AutoTG 后台守护中")
            .setContentText("持续检查 Cron 闹钟和漏触发任务，保障定时发送。")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoTG 后台守护",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于持续检查 Cron 闹钟并在系统回收进程后恢复定时任务。"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "autotg_keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "autotg_keep_alive"
        private const val PREF_ENABLED = "enabled"
        private const val WATCHDOG_WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 1000L

        const val NOTIFICATION_CHANNEL_ID = CHANNEL_ID

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var manuallyStopped: Boolean = false

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, enabled)
                .apply()
        }

        fun startIfEnabled(context: Context): Boolean {
            RecoveryScheduler.ensureRecoveryWork(context)
            return isEnabled(context) && start(context)
        }

        fun start(context: Context): Boolean {
            manuallyStopped = false
            return startWithIntent(context, Intent(context, KeepAliveService::class.java))
        }

        fun startFromRecoveryIfEnabled(context: Context): Boolean {
            if (!isEnabled(context)) return false
            manuallyStopped = false
            return startWithIntent(
                context,
                Intent(context, KeepAliveService::class.java).apply {
                    action = ACTION_EXTERNAL_RECOVERY
                }
            )
        }

        private fun startWithIntent(context: Context, intent: Intent): Boolean {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "Unable to start keep-alive service.", it)
                KeepAliveDiagnostics.recordStartFailure(context, it)
                return false
            }
            return true
        }

        fun stop(context: Context) {
            manuallyStopped = true
            RecoveryScheduler.cancelKeepAliveRestart(context)
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        private const val ACTION_EXTERNAL_RECOVERY = "com.autotg.action.EXTERNAL_RECOVERY"
    }
}
