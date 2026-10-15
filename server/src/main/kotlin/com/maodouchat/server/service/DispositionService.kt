package com.maodouchat.server.service

/**
 * Admin user disposition templates (roadmap §5.1 P0).
 * Reason codes are operational metadata only — no chat content.
 * Appeal notice is read-only copy for operators / future user-facing surfaces.
 */
object DispositionService {
    const val MAX_NOTE_CHARS = 500
    const val MAX_REASON_CODE_CHARS = 40
    const val MAX_BAN_DAYS = 3650
    /** Soft upper bound for bannedUntil relative to now (matches AdminRouting). */
    const val MAX_SUSPEND_MS: Long = MAX_BAN_DAYS * 86_400_000L
    const val MAX_POST_RESTRICT_DAYS = 90
    const val MAX_MESSAGE_RESTRICT_DAYS = 90

    /** Shown to operators; not a legal channel commitment. */
    const val APPEAL_NOTICE_ZH: String =
        "申诉说明（只读）：用户可通过应用内「帮助与反馈」提交申诉；运营在审计日志中检索 reasonCode / note 后复核。当前版本不提供自动解封工单。"

    private val codeSet: Set<String> =
        DispositionService.banReasonTemplates.map { it.code }.toSet() + DispositionService.unbanReasonCode

    fun normalizeReasonCode(raw: String?): String? =
        raw?.trim()?.lowercase()?.take(MAX_REASON_CODE_CHARS)?.takeIf { it.isNotBlank() }

    fun normalizeNote(raw: String?): String? =
        raw?.trim()?.take(MAX_NOTE_CHARS)?.takeIf { it.isNotBlank() }

    fun isKnownReasonCode(code: String?): Boolean {
        val normalized = normalizeReasonCode(code) ?: return false
        return normalized in codeSet
    }
}
