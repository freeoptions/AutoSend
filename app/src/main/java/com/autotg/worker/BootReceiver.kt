package com.autotg.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autotg.data.repository.TelegramRepository
import com.autotg.service.KeepAliveService
import com.autotg.utils.ExitReasonTracker
import com.autotg.utils.RecoveryScheduler
import com.autotg.utils.SchedulerRecovery
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        ) {
            val repository = EntryPointAccessors.fromApplication(
                context.applicationContext,
                BootReceiverDependencies::class.java
            ).repository()

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ExitReasonTracker.recordLatestExitIfNeeded(context, repository)
                    RecoveryScheduler.forceRebuildRecoveryWork(context)
                    SchedulerRecovery.recoverEnabledTasks(context, repository)
                    KeepAliveService.startIfEnabled(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootReceiverDependencies {
    fun repository(): TelegramRepository
}
