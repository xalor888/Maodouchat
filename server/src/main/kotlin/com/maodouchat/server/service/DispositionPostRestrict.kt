package com.maodouchat.server.service

// 禁动态处置：原因模板目录 + 校验/审计。

private val postRestrictReasonTemplatesData: List<PostRestrictTemplate> = listOf(
    PostRestrictTemplate("spam_feed", "垃圾动态 / 刷屏发帖", defaultDays = 1),
    PostRestrictTemplate("harassment_feed", "骚扰 / 不当内容", defaultDays = 3),
    PostRestrictTemplate("scam_feed", "引流 / 诈骗宣传", defaultDays = 7),
    PostRestrictTemplate("illegal_feed", "违法违规动态", defaultDays = 14),
    PostRestrictTemplate("impersonation_feed", "冒充 / 仿冒形象", defaultDays = 7),
    PostRestrictTemplate("other", "其他（须填写备注）", defaultDays = 1, requiresCustomNote = true)
)

internal val DispositionService.postRestrictReasonTemplates: List<PostRestrictTemplate>
    get() = postRestrictReasonTemplatesData

internal val DispositionService.unrestrictPostsReasonCode: String
    get() = "unrestrict_posts"

internal fun DispositionService.findPostRestrictTemplate(code: String?): PostRestrictTemplate? {
    val normalized = normalizeReasonCode(code) ?: return null
    return postRestrictReasonTemplates.firstOrNull { it.code == normalized }
}

internal fun DispositionService.postRestrictedUntilFromDays(days: Int, nowMs: Long): Long {
    if (days <= 0) return 0L
    return nowMs + days.toLong() * 86_400_000L
}

/**
 * @param durationDays 0 = clear post restriction; >0 temporary restrict length in whole days.
 */
internal fun DispositionService.validatePostRestrict(
    durationDays: Int,
    reasonCode: String?,
    note: String?
): DispositionValidation {
    if (durationDays < 0 || durationDays > MAX_POST_RESTRICT_DAYS) {
        return DispositionValidation.Invalid("禁动态天数无效")
    }
    val noteNorm = normalizeNote(note)
    if (durationDays == 0) {
        val code = normalizeReasonCode(reasonCode) ?: unrestrictPostsReasonCode
        if (code != unrestrictPostsReasonCode && findPostRestrictTemplate(code) == null) {
            return DispositionValidation.Invalid("未知解除禁动态原因码")
        }
        return DispositionValidation.Ok(
            reasonCode = if (code == unrestrictPostsReasonCode) unrestrictPostsReasonCode else code,
            note = noteNorm,
            banDays = 0
        )
    }
    val template = findPostRestrictTemplate(reasonCode)
        ?: return DispositionValidation.Invalid("请选择禁动态原因模板")
    if (template.requiresCustomNote && noteNorm == null) {
        return DispositionValidation.Invalid("该原因须填写备注")
    }
    return DispositionValidation.Ok(
        reasonCode = template.code,
        note = noteNorm,
        banDays = durationDays
    )
}

internal fun DispositionService.auditPostRestrictDetail(
    postRestrictedUntil: Long,
    reasonCode: String,
    note: String?
): String {
    val notePart = note?.let { "; note=$it" }.orEmpty()
    return "postRestrictedUntil=$postRestrictedUntil; reasonCode=$reasonCode$notePart"
}
