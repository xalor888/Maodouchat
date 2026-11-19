package com.maodouchat.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AiGroupTask(
    val title: String,
    val owner: String? = null,
    val dueText: String? = null,
    val dueAt: Long? = null
)

@Serializable
data class AiAuditLogResponse(
    val id: String,
    val chatId: String? = null,
    val feature: String,
    val model: String? = null,
    val status: String,
    val inputChars: Int = 0,
    val contextMessages: Int = 0,
    val durationMs: Long? = null,
    val error: String? = null,
    val createdAt: Long
)
