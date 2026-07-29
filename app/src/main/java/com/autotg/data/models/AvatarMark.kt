package com.autotg.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "avatar_marks")
data class AvatarMark(
    @PrimaryKey
    val uri: String,
    val timestamp: Long = System.currentTimeMillis()
)
