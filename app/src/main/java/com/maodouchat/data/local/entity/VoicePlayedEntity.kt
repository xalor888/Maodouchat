package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 语音消息本地已播标记（退役清单：替代 `voice_played` SharedPreferences
 * 按账号 string-set 存储；与服务端 PLAY 回执表不同，这是本机播放圆点状态）。
 */
@Entity(
    tableName = "voice_played",
    primaryKeys = ["ownerUserId", "messageId"],
    indices = [
        Index(value = ["ownerUserId"]),
    ],
)
data class VoicePlayedEntity(
    val ownerUserId: String,
    val messageId: String,
    val playedAtMillis: Long,
)
