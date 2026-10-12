package com.autosend.utils

import com.autosend.data.models.DeliveryChannel

/**
 * 界面仅展示 Telegram；QQ 的配置、任务及发送能力继续保留。
 */
object DeliveryChannelPolicy {
    private const val FEISHU_ENABLED = false

    fun isEnabled(channel: DeliveryChannel): Boolean = when (channel) {
        DeliveryChannel.QQ -> true
        DeliveryChannel.FEISHU -> FEISHU_ENABLED
        DeliveryChannel.TELEGRAM -> true
    }

    fun isVisible(channel: DeliveryChannel): Boolean = channel == DeliveryChannel.TELEGRAM
}
