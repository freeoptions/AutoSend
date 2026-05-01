package com.autotg.data.local

import androidx.room.TypeConverter
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
}
