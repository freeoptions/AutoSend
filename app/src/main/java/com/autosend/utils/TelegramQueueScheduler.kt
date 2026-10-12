package com.autosend.utils

import android.content.Context
import androidx.work.*
import com.autosend.worker.TelegramQueueWorker
import com.autosend.worker.TelegramQueueWakeWorker
import java.util.concurrent.TimeUnit

object TelegramQueueScheduler {
    fun kick(context: Context, safetyWake: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<TelegramQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("telegram_queue_drain", ExistingWorkPolicy.KEEP, request)
        // 覆盖“新消息恰好在现有 Worker 退出前入队”的 KEEP 竞态。
        if (safetyWake) wakeAt(context, System.currentTimeMillis() + 60_000L)
    }

    /** 与正在发送的 Worker 使用不同名称，重新安排唤醒不会取消正在发送的 POST。 */
    fun wakeAt(context: Context, at: Long) {
        val request = OneTimeWorkRequestBuilder<TelegramQueueWakeWorker>()
            .setInitialDelay(maxOf(1_000L, at - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("telegram_queue_wake", ExistingWorkPolicy.REPLACE, request)
    }
}
