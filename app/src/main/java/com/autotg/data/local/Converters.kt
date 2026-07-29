package com.autotg.data.local

import androidx.room.TypeConverter
import com.autotg.data.models.LogStatus
import com.autotg.data.models.MessageParseMode
import com.autotg.data.models.TaskStatus

class Converters {
    @TypeConverter
    fun fromStatus(status: TaskStatus): String {
        return status.name
    }

    @TypeConverter
    fun toStatus(status: String): TaskStatus {
        return TaskStatus.valueOf(status)
    }

    @TypeConverter
    fun fromLogStatus(status: LogStatus): String {
        return status.name
    }

    @TypeConverter
    fun toLogStatus(status: String): LogStatus {
        return LogStatus.valueOf(status)
    }

    @TypeConverter
    fun fromParseMode(parseMode: MessageParseMode): String {
        return parseMode.name
    }

    @TypeConverter
    fun toParseMode(parseMode: String): MessageParseMode {
        return runCatching { MessageParseMode.valueOf(parseMode) }
            .getOrDefault(MessageParseMode.NONE)
    }
}
