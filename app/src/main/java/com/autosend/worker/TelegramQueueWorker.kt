package com.autosend.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autosend.utils.TelegramQueueProcessor
import com.autosend.utils.TelegramQueueScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class TelegramQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: TelegramQueueProcessor
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        processor.drain()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 数据库/系统异常也不能把队列判为永久失败。
        Result.retry()
    }
}

class TelegramQueueWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TelegramQueueScheduler.kick(applicationContext, safetyWake = false)
        return Result.success()
    }
}
