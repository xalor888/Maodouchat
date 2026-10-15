package com.maodouchat.server.service

// 群禁言处置：原因模板目录 + 校验。

private val muteReasonTemplatesData: List<MuteTemplate> = listOf(
    MuteTemplate("flood", "刷屏 / 刷屏式发言", durationHours = 1),
    MuteTemplate("off_topic", "严重跑题 / 扰乱秩序", durationHours = 6),
    MuteTemplate("harassment", "言语攻击 / 骚扰", durationHours = 24),
    MuteTemplate("spam_link", "垃圾链接 / 引流", durationHours = 12),
    MuteTemplate("other", "其他（须填写备注）", durationHours = 1, requiresCustomNote = true)
)

internal val DispositionService.muteReasonTemplates: List<MuteTemplate>
    get() = muteReasonTemplatesData

internal val DispositionService.unmuteReasonCode: String
    get() = "unmute"

internal fun DispositionService.findMuteTemplate(code: String?): MuteTemplate? {
    val normalized = normalizeReasonCode(code) ?: return null
    return muteReasonTemplates.firstOrNull { it.code == normalized }
}

internal fun DispositionService.mutedUntilFromHours(hours: Int, nowMs: Long): Long {
    if (hours <= 0) return 0L
    return nowMs + hours.toLong() * 3_600_000L
}

internal fun DispositionService.validateMute(
    durationHours: Int,
    reasonCode: String?,
    note: String?
): DispositionService.DispositionValidation {
    if (durationHours < 0 || durationHours > 24 * 30) {
        return DispositionService.DispositionValidation.Invalid("禁言时长无效")
    }
    val noteNorm = normalizeNote(note)
    if (durationHours == 0) {
        return DispositionService.DispositionValidation.Ok(
            reasonCode = unmuteReasonCode,
            note = noteNorm,
            banDays = 0
        )
    }
    val template = findMuteTemplate(reasonCode)
        ?: return DispositionService.DispositionValidation.Invalid("请选择禁言原因模板")
    if (template.requiresCustomNote && noteNorm == null) {
        return DispositionService.DispositionValidation.Invalid("该原因须填写备注")
    }
    return DispositionService.DispositionValidation.Ok(
        reasonCode = template.code,
        note = noteNorm,
        banDays = 0
    )
}
