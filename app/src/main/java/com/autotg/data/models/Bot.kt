package com.autotg.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bots")
data class Bot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val token: String,
    val avatarPath: String? = null
)
