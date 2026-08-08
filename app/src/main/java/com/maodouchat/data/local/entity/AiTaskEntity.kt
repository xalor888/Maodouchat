package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_tasks",
    indices = [
        Index("chatId"),
        Index("isCompleted"),
        Index("dueAt"),
        Index(value = ["isCompleted", "remindedAt", "dueAt"])
    ]
)
data class AiTaskEntity(
    @PrimaryKey
    val id: String,
    val chatId: String,
    val sourceQuery: String,
    val title: String,
    val owner: String? = null,
    val dueText: String? = null,
    val dueAt: Long? = null,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val remindedAt: Long? = null
)
