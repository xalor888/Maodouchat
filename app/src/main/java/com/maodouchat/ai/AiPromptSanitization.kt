package com.maodouchat.ai

/** 上下文消毒簇：剥离控制字符、截断、标注不可信输入。 */
object AiPromptSanitization {

    const val MAX_CONTEXT_TEXT_CHARS = 1_800
    const val MAX_SENDER_CHARS = 120
    const val MAX_QUERY_CHARS = 700

    data class SanitizedContextLine(
        val sender: String,
        val text: String
    )

    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val ROLE_PLAY_MARKERS = Regex(
        """(?im)^\s*(system|assistant|developer|instruction)\s*[:：]"""
    )

    fun sanitizeSender(raw: String?): String =
        raw.orEmpty()
            .replace(CONTROL_CHARS, "")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(MAX_SENDER_CHARS)
            .ifBlank { "user" }

    /**
     * 消毒单条上下文：去控制符、截断、弱化伪 system 行首标记（保留原文痕迹但打散角色扮演前缀）。
     */
    fun sanitizeContextText(raw: String?, maxChars: Int = MAX_CONTEXT_TEXT_CHARS): String {
        val limit = maxChars.coerceIn(1, 4_000)
        var text = raw.orEmpty()
            .replace(CONTROL_CHARS, "")
            .trim()
        if (text.isEmpty()) return ""
        if (isLikelyInjectionAttempt(text)) return ""
        // Neutralize leading role-play markers so they cannot look like system turns.
        text = ROLE_PLAY_MARKERS.replace(text) { match ->
            "[untrusted-${match.groupValues.getOrNull(1)?.lowercase() ?: "role"}]"
        }
        return text.take(limit)
    }

    fun sanitizeQuery(raw: String?): String =
        sanitizeContextText(raw, MAX_QUERY_CHARS)

    fun sanitizeContextLine(sender: String?, text: String?, maxTextChars: Int = MAX_CONTEXT_TEXT_CHARS): SanitizedContextLine? {
        val cleanText = sanitizeContextText(text, maxTextChars)
        if (cleanText.isBlank()) return null
        return SanitizedContextLine(
            sender = sanitizeSender(sender),
            text = cleanText
        )
    }

    fun isLikelyInjectionAttempt(text: String?): Boolean {
        val body = text.orEmpty()
        if (body.isBlank()) return false
        val lower = body.lowercase()
        return lower.contains("ignore previous") ||
            lower.contains("ignore all previous") ||
            lower.contains("disregard previous") ||
            lower.contains("忽略以上") ||
            lower.contains("忽略之前") ||
            lower.contains("忽略上述") ||
            lower.contains("你现在是系统") ||
            lower.contains("you are now the system") ||
            lower.contains("developer mode")
    }
}
