package com.maodouchat.network

import kotlinx.serialization.Serializable

@Serializable
data class AiContextMessage(val sender: String = "", val text: String)

@Serializable
data class AiGroupTask(
    val title: String,
    val owner: String? = null,
    val dueText: String? = null,
    val dueAt: Long? = null
)

@Serializable
data class AiSemanticSearchCandidate(
    val messageId: String,
    val sender: String = "",
    val text: String,
    val timestamp: Long
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
