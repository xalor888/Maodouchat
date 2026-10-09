package com.maodouchat.ai

/** AI 提示注入与越权防护：薄门面，逻辑已按簇拆入 AiPromptSanitization / AiPrivilegeClaimScan。 */
object AiPromptSafetyPolicy {

    const val MAX_CONTEXT_TEXT_CHARS = AiPromptSanitization.MAX_CONTEXT_TEXT_CHARS
    const val MAX_SENDER_CHARS = AiPromptSanitization.MAX_SENDER_CHARS
    const val MAX_QUERY_CHARS = AiPromptSanitization.MAX_QUERY_CHARS
    const val MAX_OUTPUT_SCAN_CHARS = AiPrivilegeClaimScan.MAX_OUTPUT_SCAN_CHARS

    typealias SanitizedContextLine = AiPromptSanitization.SanitizedContextLine
    typealias PrivilegeClaimKind = AiPrivilegeClaimScan.PrivilegeClaimKind
    typealias PrivilegeScan = AiPrivilegeClaimScan.PrivilegeScan

    fun sanitizeSender(raw: String?): String =
        AiPromptSanitization.sanitizeSender(raw)

    fun sanitizeContextText(raw: String?, maxChars: Int = MAX_CONTEXT_TEXT_CHARS): String =
        AiPromptSanitization.sanitizeContextText(raw, maxChars)

    fun sanitizeQuery(raw: String?): String =
        AiPromptSanitization.sanitizeQuery(raw)

    fun sanitizeContextLine(
        sender: String?,
        text: String?,
        maxTextChars: Int = MAX_CONTEXT_TEXT_CHARS
    ): SanitizedContextLine? =
        AiPromptSanitization.sanitizeContextLine(sender, text, maxTextChars)

    fun isLikelyInjectionAttempt(text: String?): Boolean =
        AiPromptSanitization.isLikelyInjectionAttempt(text)

    fun scanPrivilegeClaims(output: String?): PrivilegeScan =
        AiPrivilegeClaimScan.scanPrivilegeClaims(output)

    fun annotateIfPrivilegedHallucination(output: String?, disclaimer: String): String =
        AiPrivilegeClaimScan.annotateIfPrivilegedHallucination(output, disclaimer)
}
