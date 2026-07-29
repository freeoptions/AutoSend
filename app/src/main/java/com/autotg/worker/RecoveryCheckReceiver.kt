package com.autotg.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autotg.data.repository.TelegramRepository
import com.autotg.service.KeepAliveDiagnostics
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

class RecoveryCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RecoveryScheduler.ACTION_RUN_RECOVERY_CHECK) return

        // 先注册下一棒，避免本次恢复过程中进程再次被系统终止后失去外部唤醒点。
        RecoveryScheduler.onRecoveryTriggered(
            context,
            intent.getStringExtra(RecoveryScheduler.EXTRA_RECOVERY_KIND)
        )
        if (KeepAliveService.startFromRecoveryIfEnabled(context)) {
            KeepAliveDiagnostics.recordRecoveryTriggered(context)
            return
        }

        val repository = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RecoveryCheckDependencies::class.java
        ).repository()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ExitReasonTracker.recordLatestExitIfNeeded(context, repository)
                SchedulerRecovery.recoverEnabledTasks(context, repository)
                KeepAliveDiagnostics.recordRecoveryTriggered(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecoveryCheckDependencies {
    fun repository(): TelegramRepository
}
