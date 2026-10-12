package com.autosend

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.autosend.utils.RecoveryScheduler
import com.autosend.utils.TelegramQueueScheduler
import com.autosend.utils.TelegramNetworkMonitor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AutoSendApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var telegramNetworkMonitor: TelegramNetworkMonitor

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        RecoveryScheduler.ensureRecoveryWork(this)
        TelegramQueueScheduler.kick(this)
        telegramNetworkMonitor.start()
    }
}
