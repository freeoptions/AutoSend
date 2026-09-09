package com.autosend.service

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
import com.autosend.MainActivity
import com.autosend.R
import com.autosend.utils.TaskAlarmScheduler
import com.autosend.utils.TaskExecutionLogger
import com.autosend.utils.TaskSendExecutor
import com.autosend.utils.WorkManagerHelper
import com.autosend.worker.TaskWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 只在系统闹钟到达后短暂存在的执行服务。它不使用 START_STICKY，也没有任何自启/保活逻辑。
 */
@AndroidEntryPoint
class TaskExecutionService : Service() {

    @Inject
    lateinit var taskSendExecutor: TaskSendExecutor

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeTasks = mutableMapOf<Int, ActiveTask>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0
    private var foregroundStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getLongExtra(TaskWorker.KEY_TASK_ID, -1L) ?: -1L
        val triggerAtMillis = intent?.getLongExtra(
            TaskAlarmScheduler.EXTRA_TRIGGER_AT_MILLIS,
            System.currentTimeMillis()
        ) ?: System.currentTimeMillis()
        if (taskId == -1L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        latestStartId = maxOf(latestStartId, startId)
        activeTasks[startId] = ActiveTask(taskId, triggerAtMillis)
        if (!foregroundStarted) {
            val foregroundResult = runCatching { startForegroundCompat() }
            if (foregroundResult.isFailure) {
                val failure = foregroundResult.exceptionOrNull()
                Log.w(TAG, "定时任务 shortService 进入前台失败。", failure)
                WorkManagerHelper.enqueueTaskWork(
                    context = applicationContext,
                    taskId = taskId,
                    triggerAtMillis = triggerAtMillis
                )
                serviceScope.launch {
                    TaskExecutionLogger.record(
                        applicationContext,
                        taskId,
                        TaskExecutionLogger.STAGE_SERVICE_FAILED,
                        "startForeground 失败：${failure?.message ?: failure?.javaClass?.simpleName ?: "未知异常"}；已登记 WorkManager 补偿"
                    )
                    withContext(Dispatchers.Main.immediate) {
                        finishStart(startId)
                    }
                }
                return START_NOT_STICKY
            }
            foregroundStarted = true
        }

        acquireWakeLock()
        serviceScope.launch {
            TaskExecutionLogger.record(
                applicationContext,
                taskId,
                TaskExecutionLogger.STAGE_SERVICE_STARTED,
                "shortService 已进入前台，startId=$startId"
            )
            runCatching {
                taskSendExecutor.execute(
                    taskId = taskId,
                    triggerAtMillis = triggerAtMillis
                )
            }.onFailure { throwable ->
                Log.w(TAG, "定时任务执行协程异常。", throwable)
                TaskExecutionLogger.record(
                    applicationContext,
                    taskId,
                    TaskExecutionLogger.STAGE_TASK_FAILED,
                    "执行协程异常：${throwable.message ?: throwable.javaClass.simpleName}；已登记 WorkManager 补偿"
                )
                WorkManagerHelper.enqueueTaskWork(
                    context = applicationContext,
                    taskId = taskId,
                    triggerAtMillis = triggerAtMillis
                )
            }
            withContext(Dispatchers.Main.immediate) {
                finishStart(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        val timedOutTasks = activeTasks.values.toList()
        timedOutTasks.forEach { task ->
            WorkManagerHelper.enqueueTaskWork(
                context = applicationContext,
                taskId = task.taskId,
                triggerAtMillis = task.triggerAtMillis
            )
        }
        Log.e(TAG, "shortService 超时，已登记 ${timedOutTasks.size} 条 WorkManager 补偿。")
        stopForegroundAndSelf(latestStartId.takeIf { it > 0 } ?: startId)
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        serviceScope.cancel()
        activeTasks.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun finishStart(startId: Int) {
        activeTasks.remove(startId)
        if (activeTasks.isEmpty()) {
            releaseWakeLock()
            stopForegroundAndSelf(latestStartId.takeIf { it > 0 } ?: startId)
        }
    }

    private fun stopForegroundAndSelf(startId: Int) {
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf(startId)
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
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
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AutoSend 正在发送定时消息")
            .setContentText("任务完成后会立即移除本通知。")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "AutoSend 定时发送",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "仅在到点发送定时消息时短暂显示。"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class ActiveTask(
        val taskId: Long,
        val triggerAtMillis: Long
    )

    companion object {
        private const val TAG = "TaskExecutionService"
        const val NOTIFICATION_CHANNEL_ID = "autosend_task_execution"
        private const val NOTIFICATION_ID = 1002
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 1000L

        fun start(
            context: Context,
            taskId: Long,
            triggerAtMillis: Long
        ): Result<Unit> {
            val intent = Intent(context, TaskExecutionService::class.java).apply {
                putExtra(TaskWorker.KEY_TASK_ID, taskId)
                putExtra(TaskAlarmScheduler.EXTRA_TRIGGER_AT_MILLIS, triggerAtMillis)
            }
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Unit
            }.onFailure {
                Log.w(TAG, "无法启动定时任务 shortService。", it)
            }
        }
    }
}
