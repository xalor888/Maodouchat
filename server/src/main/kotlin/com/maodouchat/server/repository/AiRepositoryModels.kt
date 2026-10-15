package com.maodouchat.server.repository

data class AiAuditExportRow(
    val id: String,
    val userId: String,
    val feature: String,
    val status: String,
    val inputChars: Int,
    val contextMessages: Int,
    val durationMs: Long?,
    val error: String?,
    val createdAt: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
)
