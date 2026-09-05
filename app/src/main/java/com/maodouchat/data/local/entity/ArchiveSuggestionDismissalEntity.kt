package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 智能归档建议忽略记录（P10/退役清单：替代 `archive_suggestion_dismiss`
 * SharedPreferences 按账号 string-set 存储，随 M09 定时/提醒表之后迁入 Room）。
 */
@Entity(
    tableName = "archive_suggestion_dismissals",
    primaryKeys = ["ownerUserId", "chatId"],
    indices = [
        Index(value = ["ownerUserId"]),
    ],
)
data class ArchiveSuggestionDismissalEntity(
    val ownerUserId: String,
    val chatId: String,
    val dismissedAtMillis: Long,
)
