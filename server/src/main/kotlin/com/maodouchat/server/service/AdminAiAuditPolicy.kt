package com.maodouchat.server.service

import com.maodouchat.server.model.AiUsageAdminResponse

/**
 * M5-8：管理端 AI 审计只允许元数据（功能、模型、状态、输入规模、延迟、错误码级文案）。
 * 禁止把 prompt / 正文 / 聊天内容投影进管理端响应。
 */
object AdminAiAuditPolicy {
    const val MAX_LIMIT = 250
    const val DEFAULT_LIMIT = 60
    const val MAX_FEATURE_FILTER_CHARS = 40
    const val MAX_USER_FILTER_CHARS = 50
    const val MAX_ERROR_CHARS = 280

    /** Soft token estimate for ops dashboards only (not billed tokens). */
    fun estimatedTokensFromInputChars(inputChars: Int): Int {
        val chars = inputChars.coerceAtLeast(0)
        if (chars == 0) return 0
        return ((chars + 3) / 4).coerceAtLeast(1)
    }

    /**
     * 优先使用上游返回的真实 token；仅当两者都为 null（旧数据/旧调用方）时
     * 才回退到按输入字符数的粗略估算。
     */
    fun resolveEstimatedTokens(
        inputTokens: Long?,
        outputTokens: Long?,
        inputChars: Int
    ): Int {
        val chars = inputChars.coerceAtLeast(0)
        return if (inputTokens != null || outputTokens != null) {
            ((inputTokens ?: 0L) + (outputTokens ?: 0L)).toInt().coerceAtLeast(0)
        } else {
            estimatedTokensFromInputChars(chars)
        }
    }

    fun normalizeLimit(raw: Int?): Int = (raw ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    fun normalizeOffset(raw: Long?): Long = (raw ?: 0L).coerceAtLeast(0L)

    fun normalizeFeatureFilter(raw: String?): String? =
        raw?.trim()?.take(MAX_FEATURE_FILTER_CHARS)?.takeIf { it.isNotBlank() && it != "ALL" }

    fun normalizeUserFilter(raw: String?): String? =
        raw?.trim()?.take(MAX_USER_FILTER_CHARS)?.takeIf { it.isNotBlank() }

    /**
     * Project a stored audit row into the admin DTO. Intentionally omits chatId and any body fields.
     *
     * 真实 token 由调用方按需传入（来自 ai_audit_logs 的 input_tokens / output_tokens 列）。
     * 为保持向后兼容，两个参数均可空：为空时回退到按输入字符数的粗略估算。
     */
    fun toAdminResponse(
        id: String,
        userId: String,
        feature: String,
        model: String?,
        status: String,
        inputChars: Int,
        contextMessages: Int,
        durationMs: Long?,
        error: String?,
        createdAt: Long,
        inputTokens: Long? = null,
        outputTokens: Long? = null
    ): AiUsageAdminResponse {
        val safeInput = inputChars.coerceAtLeast(0)
        return AiUsageAdminResponse(
            id = id.take(100),
            userId = userId.take(MAX_USER_FILTER_CHARS),
            feature = feature.take(MAX_FEATURE_FILTER_CHARS),
            model = model?.take(80),
            status = status.take(30),
            inputChars = safeInput,
            contextMessages = contextMessages.coerceAtLeast(0),
            durationMs = durationMs?.coerceAtLeast(0L),
            error = error?.take(MAX_ERROR_CHARS),
            createdAt = createdAt,
            estimatedTokens = resolveEstimatedTokens(inputTokens, outputTokens, safeInput)
        )
    }

    /** Fields that must never appear on admin AI audit payloads. */
    val forbiddenPayloadKeys: Set<String> = setOf(
        "prompt",
        "body",
        "text",
        "content",
        "messages",
        "chatId",
        "chat_id",
        "rewrite",
        "summary",
        "transcript"
    )

    fun isMetadataOnlyResponse(keys: Set<String>): Boolean =
        keys.none { forbiddenPayloadKeys.contains(it) }
}
