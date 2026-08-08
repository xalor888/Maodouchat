package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "chat_drafts",
    primaryKeys = ["ownerUserId", "chatId"],
    indices = [Index("ownerUserId"), Index("updatedAt")]
)
data class ChatDraftEntity(
    val ownerUserId: String,
    val chatId: String,
    val text: String,
    val updatedAt: Long
)
