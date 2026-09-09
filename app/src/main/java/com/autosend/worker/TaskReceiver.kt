package com.autosend.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.autosend.service.TaskExecutionService
import com.autosend.utils.TaskAlarmScheduler
import com.autosend.utils.TaskExecutionLogger
import com.autosend.utils.TaskSendExecutor
import com.autosend.utils.WorkManagerHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 清单静态注册的显式闹钟入口，进程不存在时由系统创建应用进程后调用。 */
class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskAlarmScheduler.ACTION_RUN_TASK) return

        val taskId = intent.getLongOfExtra(TaskWorker.KEY_TASK_ID, -1L)
        if (taskId == -1L) return
        val triggerAtMillis = intent.getLongOfExtra(
            TaskAlarmScheduler.EXTRA_TRIGGER_AT_MILLIS,
            System.currentTimeMillis()
        )
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 必须先落库，后续即使 FGS 启动被系统拒绝，也能确认 AlarmManager 已完成投递。
                TaskExecutionLogger.record(
                    appContext,
                    taskId,
                    TaskExecutionLogger.STAGE_ALARM_RECEIVED,
                    "triggerAtMillis=$triggerAtMillis，receivedAtMillis=${System.currentTimeMillis()}"
                )

                val serviceStart = TaskExecutionService.start(
                    context = appContext,
                    taskId = taskId,
                    triggerAtMillis = triggerAtMillis
                )
                if (serviceStart.isSuccess) {
                    TaskExecutionLogger.record(
                        appContext,
                        taskId,
                        TaskExecutionLogger.STAGE_SERVICE_STARTED,
                        "startForegroundService 已提交，等待 shortService 进入前台"
                    )
                    return@launch
                }

                val failure = serviceStart.exceptionOrNull()
                TaskExecutionLogger.record(
                    appContext,
                    taskId,
                    TaskExecutionLogger.STAGE_SERVICE_FAILED,
                    "${failure?.javaClass?.simpleName ?: "未知异常"}=${failure?.message ?: "无详细信息"}；Receiver 启动直接执行降级"
                )

                // 先登记可延迟补偿，随后在 Receiver 的 goAsync 生命周期内直接尝试执行。
                WorkManagerHelper.enqueueTaskWork(
                    context = appContext,
                    taskId = taskId,
                    triggerAtMillis = triggerAtMillis
                )
                executeFallback(appContext, taskId, triggerAtMillis)
            } catch (throwable: Throwable) {
                Log.e(TAG, "Receiver 定时任务链路异常，taskId=$taskId", throwable)
                WorkManagerHelper.enqueueTaskWork(
                    context = appContext,
                    taskId = taskId,
                    triggerAtMillis = triggerAtMillis
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun executeFallback(
        context: Context,
        taskId: Long,
        triggerAtMillis: Long
    ) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:receiver-fallback"
        ).apply {
            setReferenceCounted(false)
            acquire(FALLBACK_WAKE_LOCK_TIMEOUT_MS)
        }
        try {
            val executor = EntryPointAccessors.fromApplication(
                context,
                TaskReceiverDependencies::class.java
            ).taskSendExecutor()
            executor.execute(taskId, triggerAtMillis)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun Intent.getLongOfExtra(key: String, defaultValue: Long): Long {
        return if (hasExtra(key)) getLongExtra(key, defaultValue) else defaultValue
    }

    private companion object {
        const val TAG = "TaskReceiver"
        const val FALLBACK_WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 1000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TaskReceiverDependencies {
    fun taskSendExecutor(): TaskSendExecutor
}
