package com.autosend.utils

import com.autosend.data.models.DeliveryChannel

/**
 * 控制当前版本允许在日常界面和调度器中使用的发送通道。
 * 飞书数据仍保留在数据库和配置导出中，但暂时不参与日常发送。
 */
object DeliveryChannelPolicy {
    private const val FEISHU_ENABLED = false

    fun isEnabled(channel: DeliveryChannel): Boolean = when (channel) {
        DeliveryChannel.QQ -> true
        DeliveryChannel.FEISHU -> FEISHU_ENABLED
        DeliveryChannel.TELEGRAM -> false
    }
}
