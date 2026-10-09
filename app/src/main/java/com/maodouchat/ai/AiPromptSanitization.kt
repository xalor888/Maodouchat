package com.maodouchat.ai

/** 上下文消毒门面：文本消毒簇 + 注入检测簇的统一入口，全部透传。 */
object AiPromptSanitization {

    const val MAX_CONTEXT_TEXT_CHARS = AiPromptSanitizeText.MAX_CONTEXT_TEXT_CHARS
    const val MAX_SENDER_CHARS = AiPromptSanitizeText.MAX_SENDER_CHARS
    const val MAX_QUERY_CHARS = AiPromptSanitizeText.MAX_QUERY_CHARS

    typealias SanitizedContextLine = AiPromptSanitizeText.SanitizedContextLine

    fun sanitizeSender(raw: String?): String =
        AiPromptSanitizeText.sanitizeSender(raw)

    fun sanitizeContextText(raw: String?, maxChars: Int = MAX_CONTEXT_TEXT_CHARS): String =
        AiPromptSanitizeText.sanitizeContextText(raw, maxChars)

    fun sanitizeQuery(raw: String?): String =
        AiPromptSanitizeText.sanitizeQuery(raw)

    fun sanitizeContextLine(
        sender: String?,
        text: String?,
        maxTextChars: Int = MAX_CONTEXT_TEXT_CHARS
    ): SanitizedContextLine? =
        AiPromptSanitizeText.sanitizeContextLine(sender, text, maxTextChars)

    fun isLikelyInjectionAttempt(text: String?): Boolean =
        AiPromptInjectionDetection.isLikelyInjectionAttempt(text)
}
