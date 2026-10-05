package com.autosend.utils

import android.content.Context
import android.util.Log
import com.autosend.data.local.AppDatabase
import com.autosend.data.models.LogStatus
import com.autosend.data.models.TaskLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 将定时链路的关键阶段写入 Room。系统 Logcat 可能随进程消失，不能作为唯一诊断依据。
 */
object TaskExecutionLogger {
    private const val TAG = "AutoSendTaskPipeline"
    private const val SYSTEM_TASK_ID = -998L

    const val STAGE_SCHEDULE_SUBMITTED = "调度提交"
    const val STAGE_SCHEDULE_FAILED = "调度提交失败"
    const val STAGE_ALARM_RECEIVED = "Receiver 收到系统闹钟"
    const val STAGE_SERVICE_STARTED = "短时服务启动"
    const val STAGE_SERVICE_FAILED = "短时服务启动失败"
    const val STAGE_TASK_STARTED = "实际任务开始"
    const val STAGE_TASK_SUCCEEDED = "实际任务成功"
    const val STAGE_TASK_FAILED = "实际任务失败"
    const val STAGE_NEXT_SCHEDULE_SUBMITTED = "下一次任务提交"

    suspend fun record(
        context: Context,
        taskId: Long,
        stage: String,
        details: String,
        status: LogStatus = LogStatus.SYSTEM
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dao = AppDatabase.getDatabase(context.applicationContext).autoSendDao()
            val task = dao.getTaskById(taskId)
            val bot = task?.botId?.let { dao.getBotById(it) }
            val chat = task?.chatId?.let { dao.getChatById(it) }
            val webhook = task?.feishuWebhookId?.let { dao.getFeishuWebhookById(it) }
            val qqBot = task?.qqBotId?.let { dao.getQqBotById(it) }
            dao.insertLog(
                TaskLog(
                    taskId = if (task != null) taskId else SYSTEM_TASK_ID,
                    taskContent = "[$stage] ${task?.content ?: "任务 $taskId"}",
                    botName = when (task?.deliveryChannel) {
                        com.autosend.data.models.DeliveryChannel.FEISHU -> "飞书"
                        com.autosend.data.models.DeliveryChannel.QQ -> "QQ 群"
                        else -> bot?.name ?: "AutoSend"
                    },
                    chatName = when (task?.deliveryChannel) {
                        com.autosend.data.models.DeliveryChannel.FEISHU -> webhook?.name ?: "未知飞书群"
                        com.autosend.data.models.DeliveryChannel.QQ -> qqBot?.name ?: "未知 QQ 群"
                        else -> chat?.name ?: "定时任务链路"
                    },
                    status = status,
                    errorMessage = details
                )
            )
            Log.i(TAG, "[$stage] taskId=$taskId, $details")
            true
        }.getOrElse { throwable ->
            Log.e(TAG, "[$stage] 持久化日志失败，taskId=$taskId, $details", throwable)
            false
        }
    }
}
