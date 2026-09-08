package com.autotg.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val chatId: String
)
