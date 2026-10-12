package com.maodouchat.server.service

// 封禁处置：原因模板目录 + 校验/审计（模板数据单例化，扩展属性只做只读转发）。

private val banReasonTemplatesData: List<DispositionService.ReasonTemplate> = listOf(
    DispositionService.ReasonTemplate("spam", "垃圾广告 / 引流", defaultDays = 7),
    DispositionService.ReasonTemplate("harassment", "骚扰 / 辱骂", defaultDays = 14),
    DispositionService.ReasonTemplate("scam", "诈骗 / 欺诈", defaultDays = 30),
    DispositionService.ReasonTemplate("illegal", "违法违规内容", defaultDays = 90),
    DispositionService.ReasonTemplate("impersonation", "冒充他人 / 仿冒", defaultDays = 30),
    DispositionService.ReasonTemplate("abuse_api", "滥用接口 / 自动化", defaultDays = 7),
    DispositionService.ReasonTemplate("other", "其他（须填写备注）", defaultDays = 1, requiresCustomNote = true)
)

internal val DispositionService.banReasonTemplates: List<DispositionService.ReasonTemplate>
    get() = banReasonTemplatesData

internal val DispositionService.unbanReasonCode: String
    get() = "unban"

internal fun DispositionService.findTemplate(code: String?): DispositionService.ReasonTemplate? {
    val normalized = normalizeReasonCode(code) ?: return null
    return banReasonTemplates.firstOrNull { it.code == normalized }
}

/**
 * @param banDays 0 = unban; >0 temporary ban length in whole days.
 */
internal fun DispositionService.validateDisposition(
    banDays: Int,
    reasonCode: String?,
    note: String?
): DispositionService.DispositionValidation {
    if (banDays < 0 || banDays > MAX_BAN_DAYS) {
        return DispositionService.DispositionValidation.Invalid("封禁天数无效")
    }
    val code = normalizeReasonCode(reasonCode)
    val noteNorm = normalizeNote(note)
    if (banDays == 0) {
        // Unban: optional note; reason defaults to unban
        val effective = code ?: unbanReasonCode
        if (effective != unbanReasonCode && !isKnownReasonCode(effective)) {
            return DispositionService.DispositionValidation.Invalid("未知解封原因码")
        }
        return DispositionService.DispositionValidation.Ok(
            reasonCode = if (effective == unbanReasonCode) unbanReasonCode else effective,
            note = noteNorm,
            banDays = 0
        )
    }
    if (code == null || !isKnownReasonCode(code) || code == unbanReasonCode) {
        return DispositionService.DispositionValidation.Invalid("请选择封禁原因模板")
    }
    val template = findTemplate(code)
        ?: return DispositionService.DispositionValidation.Invalid("请选择封禁原因模板")
    if (template.requiresCustomNote && noteNorm == null) {
        return DispositionService.DispositionValidation.Invalid("该原因须填写备注")
    }
    return DispositionService.DispositionValidation.Ok(
        reasonCode = code,
        note = noteNorm,
        banDays = banDays
    )
}

internal fun DispositionService.auditDetail(
    bannedUntil: Long,
    reasonCode: String,
    note: String?
): String {
    val notePart = note?.let { "; note=$it" }.orEmpty()
    return "bannedUntil=$bannedUntil; reasonCode=$reasonCode$notePart"
}

internal fun DispositionService.bannedUntilFromDays(banDays: Int, nowMs: Long): Long {
    if (banDays <= 0) return 0L
    return nowMs + banDays.toLong() * 86_400_000L
}
