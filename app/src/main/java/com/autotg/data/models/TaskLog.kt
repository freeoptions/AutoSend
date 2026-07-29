package com.autotg.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_logs")
data class TaskLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val taskContent: String,
    val botName: String,
    val chatName: String,
    val status: LogStatus,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class LogStatus {
    SUCCESS,
    FAILED,
    MISSED,
    SYSTEM
}
