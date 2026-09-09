package com.autosend.data.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLogTest {
    @Test
    fun unreadResultRequiresReadUntilOpened() {
        val unreadSuccess = log(LogStatus.SUCCESS)
        val unreadFailure = log(LogStatus.FAILED)
        val readSuccess = unreadSuccess.copy(isRead = true)

        assertTrue(unreadSuccess.isUnreadResult())
        assertTrue(unreadFailure.isUnreadResult())
        assertFalse(readSuccess.isUnreadResult())
    }

    @Test
    fun systemDiagnosticIsNotShownAsUnreadResult() {
        assertFalse(log(LogStatus.SYSTEM).isUnreadResult())
    }

    private fun log(status: LogStatus): TaskLog = TaskLog(
        taskId = 1L,
        taskContent = "示例任务",
        botName = "AutoSend",
        chatName = "定时任务链路",
        status = status
    )
}
