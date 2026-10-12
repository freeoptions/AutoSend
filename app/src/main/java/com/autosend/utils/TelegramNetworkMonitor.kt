package com.autosend.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.room.withTransaction
import com.autosend.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastWake = AtomicLong(-30_000L)
    private val prefs = context.getSharedPreferences("autosend_network_monitor", Context.MODE_PRIVATE)
    private val lastHandle = AtomicLong(prefs.getLong("last_handle", Long.MIN_VALUE))

    fun start() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                val previousHandle = lastHandle.getAndSet(handle)
                prefs.edit().putLong("last_handle", handle).apply()
                // 注册回调会立即报告当前网络，不能把每次进程重启都当成网络恢复。
                if (previousHandle == handle || previousHandle == Long.MIN_VALUE) return
                val now = SystemClock.elapsedRealtime()
                val previous = lastWake.get()
                if (now - previous < 30_000L || !lastWake.compareAndSet(previous, now)) return
                scope.launch {
                    // 网络切换仅允许提前一次检测，失败次数保留，不能视为线路已经恢复。
                    database.withTransaction {
                        val dao = database.autoSendDao()
                        dao.getDeliveryGate("network")?.takeIf { it.failures > 0 }?.let {
                            dao.putDeliveryGate(it.copy(untilMillis = 0))
                        }
                    }
                    TelegramQueueScheduler.kick(context)
                }
            }
        })
    }
}
