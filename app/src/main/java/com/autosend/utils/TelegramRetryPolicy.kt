package com.autosend.utils

object TelegramRetryPolicy {
    fun networkDelayMillis(failures: Int): Long = when (failures) {
        1 -> 30_000L
        2 -> 120_000L
        3 -> 300_000L
        4 -> 900_000L
        5 -> 1_800_000L
        else -> 3_600_000L
    }

    fun messageIntervalMillis(chatType: String?, chatId: String): Long =
        if (chatType == "private" || (chatType == null && chatId.toLongOrNull()?.let { it > 0 } == true)) {
            2_000L
        } else {
            5_000L
        }
}
