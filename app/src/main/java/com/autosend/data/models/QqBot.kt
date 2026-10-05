package com.autosend.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * QQ 官方机器人及其绑定的群目标。
 * groupOpenId 与机器人绑定，不等同于普通 QQ 群号。
 */
@Entity(tableName = "qq_bots")
data class QqBot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val appId: String,
    val clientSecret: String,
    val groupOpenId: String
)
