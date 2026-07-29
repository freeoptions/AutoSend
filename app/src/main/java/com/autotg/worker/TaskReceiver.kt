package com.autotg.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autotg.service.TaskExecutionService
import com.autotg.utils.TaskAlarmScheduler
import com.autotg.utils.WorkManagerHelper

class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskAlarmScheduler.ACTION_RUN_TASK) return

        val taskId = intent.getLongOfExtra(TelegramWorker.KEY_TASK_ID, -1L)
        if (taskId != -1L) {
            val serviceStarted = TaskExecutionService.start(context, taskId)
            if (!serviceStarted) {
                WorkManagerHelper.enqueueTaskWork(context, taskId)
            }
        }
    }

    private fun Intent.getLongOfExtra(key: String, defaultValue: Long): Long {
        return if (hasExtra(key)) getLongExtra(key, defaultValue) else defaultValue
    }
}
