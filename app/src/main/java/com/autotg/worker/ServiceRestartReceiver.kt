package com.autotg.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autotg.service.KeepAliveService
import com.autotg.utils.RecoveryScheduler

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RecoveryScheduler.ACTION_RESTART_KEEP_ALIVE) return
        KeepAliveService.startIfEnabled(context)
    }
}
