package com.maodouchat.server.service

// 禁言（私聊/群聊发言）处置：原因模板目录 + 校验/审计。

private val messageRestrictReasonTemplatesData: List<DispositionService.MessageRestrictTemplate> = listOf(
    DispositionService.MessageRestrictTemplate("spam_chat", "spam / flood messages", defaultDays = 1),
    DispositionService.MessageRestrictTemplate("harassment_chat", "harassment in chat", defaultDays = 3),
    DispositionService.MessageRestrictTemplate("scam_chat", "scam / fraud chat", defaultDays = 7),
    DispositionService.MessageRestrictTemplate("illegal_chat", "illegal content chat", defaultDays = 14),
    DispositionService.MessageRestrictTemplate("abuse_api", "api / bot abuse", defaultDays = 3),
    DispositionService.MessageRestrictTemplate("other", "other (note required)", defaultDays = 1, requiresCustomNote = true)
)

internal val DispositionService.messageRestrictReasonTemplates: List<DispositionService.MessageRestrictTemplate>
    get() = messageRestrictReasonTemplatesData

internal val DispositionService.unrestrictMessagesReasonCode: String
    get() = "unrestrict_messages"

internal fun DispositionService.findMessageRestrictTemplate(code: String?): DispositionService.MessageRestrictTemplate? {
    val normalized = normalizeReasonCode(code) ?: return null
    return messageRestrictReasonTemplates.firstOrNull { it.code == normalized }
}

internal fun DispositionService.messageRestrictedUntilFromDays(days: Int, nowMs: Long): Long {
    if (days <= 0) return 0L
    return nowMs + days.toLong() * 86_400_000L
}

internal fun DispositionService.validateMessageRestrict(
    durationDays: Int,
    reasonCode: String?,
    note: String?
): DispositionService.DispositionValidation {
    if (durationDays < 0 || durationDays > MAX_MESSAGE_RESTRICT_DAYS) {
        return DispositionService.DispositionValidation.Invalid("invalid message restrict duration")
    }
    val noteNorm = normalizeNote(note)
    if (durationDays == 0) {
        val code = normalizeReasonCode(reasonCode) ?: unrestrictMessagesReasonCode
        if (code != unrestrictMessagesReasonCode && findMessageRestrictTemplate(code) == null) {
            return DispositionService.DispositionValidation.Invalid("unknown unrestrict messages reason")
        }
        return DispositionService.DispositionValidation.Ok(
            reasonCode = if (code == unrestrictMessagesReasonCode) unrestrictMessagesReasonCode else code,
            note = noteNorm,
            banDays = 0
        )
    }
    val template = findMessageRestrictTemplate(reasonCode)
        ?: return DispositionService.DispositionValidation.Invalid("unknown message restrict reason")
    if (template.requiresCustomNote && noteNorm == null) {
        return DispositionService.DispositionValidation.Invalid("note required for this reason")
    }
    return DispositionService.DispositionValidation.Ok(
        reasonCode = template.code,
        note = noteNorm,
        banDays = 0
    )
}

internal fun DispositionService.auditMessageRestrictDetail(
    messageRestrictedUntil: Long,
    reasonCode: String,
    note: String?
): String {
    val notePart = note?.let { "; note=$it" }.orEmpty()
    return "messageRestrictedUntil=$messageRestrictedUntil; reasonCode=$reasonCode$notePart"
}
