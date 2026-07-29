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
import com.autotg.utils.TaskSendExecutor
import com.autotg.utils.WorkManagerHelper
import com.autotg.worker.TelegramWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class TaskExecutionService : Service() {

    @Inject
    lateinit var taskSendExecutor: TaskSendExecutor

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeExecutionCount = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getLongExtra(TelegramWorker.KEY_TASK_ID, -1L) ?: -1L
        if (taskId == -1L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val foregroundResult = runCatching { startForegroundCompat() }
        if (foregroundResult.isFailure) {
            Log.w(TAG, "定时任务前台服务进入前台失败，已转交 WorkManager。", foregroundResult.exceptionOrNull())
            WorkManagerHelper.enqueueTaskWork(applicationContext, taskId)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        activeExecutionCount++
        acquireWakeLock()
        serviceScope.launch {
            runCatching { taskSendExecutor.execute(taskId) }
                .onFailure { Log.w(TAG, "定时任务发送失败。", it) }
            withContext(Dispatchers.Main.immediate) {
                activeExecutionCount--
                if (activeExecutionCount == 0) {
                    releaseWakeLock()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:task-execution"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun startForegroundCompat() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
            .setContentTitle("AutoTG 正在发送定时消息")
            .setContentText("任务完成后将自动结束本次网络操作。")
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
            "AutoTG 定时发送",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "仅在到点发送 Telegram 消息时短暂运行。"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TaskExecutionService"
        private const val CHANNEL_ID = "autotg_task_execution"
        private const val NOTIFICATION_ID = 1002
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 1000L

        fun start(context: Context, taskId: Long): Boolean {
            val intent = Intent(context, TaskExecutionService::class.java).apply {
                putExtra(TelegramWorker.KEY_TASK_ID, taskId)
            }
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.getOrElse {
                Log.w(TAG, "无法启动定时任务前台服务。", it)
                false
            }
        }
    }
}
