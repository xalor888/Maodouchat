package com.maodouchat.server.service

/**
 * Admin user disposition templates (roadmap §5.1 P0).
 * Reason codes are operational metadata only — no chat content.
 * Appeal notice is read-only copy for operators / future user-facing surfaces.
 */
object AdminDispositionPolicy {
    const val MAX_NOTE_CHARS = 500
    const val MAX_REASON_CODE_CHARS = 40
    const val MAX_BAN_DAYS = 3650
    /** Soft upper bound for bannedUntil relative to now (matches AdminRouting). */
    const val MAX_SUSPEND_MS: Long = MAX_BAN_DAYS * 86_400_000L

    data class ReasonTemplate(
        val code: String,
        val labelZh: String,
        val defaultDays: Int,
        val requiresCustomNote: Boolean = false
    )

    val banReasonTemplates: List<ReasonTemplate> = listOf(
        ReasonTemplate("spam", "垃圾广告 / 引流", defaultDays = 7),
        ReasonTemplate("harassment", "骚扰 / 辱骂", defaultDays = 14),
        ReasonTemplate("scam", "诈骗 / 欺诈", defaultDays = 30),
        ReasonTemplate("illegal", "违法违规内容", defaultDays = 90),
        ReasonTemplate("impersonation", "冒充他人 / 仿冒", defaultDays = 30),
        ReasonTemplate("abuse_api", "滥用接口 / 自动化", defaultDays = 7),
        ReasonTemplate("other", "其他（须填写备注）", defaultDays = 1, requiresCustomNote = true)
    )

    /**
     * Group mute templates (hours). Used by admin catalog + future group-admin UX;
     * site-wide ban still uses [banReasonTemplates].
     */
    data class MuteTemplate(
        val code: String,
        val labelZh: String,
        val durationHours: Int,
        val requiresCustomNote: Boolean = false
    )

    val muteReasonTemplates: List<MuteTemplate> = listOf(
        MuteTemplate("flood", "刷屏 / 刷屏式发言", durationHours = 1),
        MuteTemplate("off_topic", "严重跑题 / 扰乱秩序", durationHours = 6),
        MuteTemplate("harassment", "言语攻击 / 骚扰", durationHours = 24),
        MuteTemplate("spam_link", "垃圾链接 / 引流", durationHours = 12),
        MuteTemplate("other", "其他（须填写备注）", durationHours = 1, requiresCustomNote = true)
    )

    /**
     * Site-wide post (moments) restriction templates (days).
     * Enforced by rejectIfPostRestricted on create/upload paths.
     */
    data class PostRestrictTemplate(
        val code: String,
        val labelZh: String,
        val defaultDays: Int,
        val requiresCustomNote: Boolean = false
    )

    const val MAX_POST_RESTRICT_DAYS = 90

    val postRestrictReasonTemplates: List<PostRestrictTemplate> = listOf(
        PostRestrictTemplate("spam_feed", "垃圾动态 / 刷屏发帖", defaultDays = 1),
        PostRestrictTemplate("harassment_feed", "骚扰 / 不当内容", defaultDays = 3),
        PostRestrictTemplate("scam_feed", "引流 / 诈骗宣传", defaultDays = 7),
        PostRestrictTemplate("illegal_feed", "违法违规动态", defaultDays = 14),
        PostRestrictTemplate("impersonation_feed", "冒充 / 仿冒形象", defaultDays = 7),
        PostRestrictTemplate("other", "其他（须填写备注）", defaultDays = 1, requiresCustomNote = true)
    )

    val unbanReasonCode: String = "unban"
    val unmuteReasonCode: String = "unmute"
    val unrestrictPostsReasonCode: String = "unrestrict_posts"

    data class MessageRestrictTemplate(
        val code: String,
        val labelZh: String,
        val defaultDays: Int,
        val requiresCustomNote: Boolean = false
    )

    const val MAX_MESSAGE_RESTRICT_DAYS = 90

    val messageRestrictReasonTemplates: List<MessageRestrictTemplate> = listOf(
        MessageRestrictTemplate("spam_chat", "spam / flood messages", defaultDays = 1),
        MessageRestrictTemplate("harassment_chat", "harassment in chat", defaultDays = 3),
        MessageRestrictTemplate("scam_chat", "scam / fraud chat", defaultDays = 7),
        MessageRestrictTemplate("illegal_chat", "illegal content chat", defaultDays = 14),
        MessageRestrictTemplate("abuse_api", "api / bot abuse", defaultDays = 3),
        MessageRestrictTemplate("other", "other (note required)", defaultDays = 1, requiresCustomNote = true)
    )

    val unrestrictMessagesReasonCode: String = "unrestrict_messages"


    /** Shown to operators; not a legal channel commitment. */
    const val APPEAL_NOTICE_ZH: String =
        "申诉说明（只读）：用户可通过应用内「帮助与反馈」提交申诉；运营在审计日志中检索 reasonCode / note 后复核。当前版本不提供自动解封工单。"

    private val codeSet: Set<String> =
        banReasonTemplates.map { it.code }.toSet() + unbanReasonCode

    fun findTemplate(code: String?): ReasonTemplate? {
        val normalized = normalizeReasonCode(code) ?: return null
        return banReasonTemplates.firstOrNull { it.code == normalized }
    }

    fun normalizeReasonCode(raw: String?): String? =
        raw?.trim()?.lowercase()?.take(MAX_REASON_CODE_CHARS)?.takeIf { it.isNotBlank() }

    fun normalizeNote(raw: String?): String? =
        raw?.trim()?.take(MAX_NOTE_CHARS)?.takeIf { it.isNotBlank() }

    fun isKnownReasonCode(code: String?): Boolean {
        val normalized = normalizeReasonCode(code) ?: return false
        return normalized in codeSet
    }

    /**
     * @param banDays 0 = unban; >0 temporary ban length in whole days.
     */
    fun validateDisposition(
        banDays: Int,
        reasonCode: String?,
        note: String?
    ): DispositionValidation {
        if (banDays < 0 || banDays > MAX_BAN_DAYS) {
            return DispositionValidation.Invalid("封禁天数无效")
        }
        val code = normalizeReasonCode(reasonCode)
        val noteNorm = normalizeNote(note)
        if (banDays == 0) {
            // Unban: optional note; reason defaults to unban
            val effective = code ?: unbanReasonCode
            if (effective != unbanReasonCode && !isKnownReasonCode(effective)) {
                return DispositionValidation.Invalid("未知解封原因码")
            }
            return DispositionValidation.Ok(
                reasonCode = if (effective == unbanReasonCode) unbanReasonCode else effective,
                note = noteNorm,
                banDays = 0
            )
        }
        if (code == null || !isKnownReasonCode(code) || code == unbanReasonCode) {
            return DispositionValidation.Invalid("请选择封禁原因模板")
        }
        val template = findTemplate(code)
            ?: return DispositionValidation.Invalid("请选择封禁原因模板")
        if (template.requiresCustomNote && noteNorm == null) {
            return DispositionValidation.Invalid("该原因须填写备注")
        }
        return DispositionValidation.Ok(
            reasonCode = code,
            note = noteNorm,
            banDays = banDays
        )
    }

    fun auditDetail(
        bannedUntil: Long,
        reasonCode: String,
        note: String?
    ): String {
        val notePart = note?.let { "; note=$it" }.orEmpty()
        return "bannedUntil=$bannedUntil; reasonCode=$reasonCode$notePart"
    }

    fun bannedUntilFromDays(banDays: Int, nowMs: Long): Long {
        if (banDays <= 0) return 0L
        return nowMs + banDays.toLong() * 86_400_000L
    }

    fun mutedUntilFromHours(hours: Int, nowMs: Long): Long {
        if (hours <= 0) return 0L
        return nowMs + hours.toLong() * 3_600_000L
    }

    fun findMuteTemplate(code: String?): MuteTemplate? {
        val normalized = normalizeReasonCode(code) ?: return null
        return muteReasonTemplates.firstOrNull { it.code == normalized }
    }

    fun validateMute(
        durationHours: Int,
        reasonCode: String?,
        note: String?
    ): DispositionValidation {
        if (durationHours < 0 || durationHours > 24 * 30) {
            return DispositionValidation.Invalid("禁言时长无效")
        }
        val noteNorm = normalizeNote(note)
        if (durationHours == 0) {
            return DispositionValidation.Ok(
                reasonCode = unmuteReasonCode,
                note = noteNorm,
                banDays = 0
            )
        }
        val template = findMuteTemplate(reasonCode)
            ?: return DispositionValidation.Invalid("请选择禁言原因模板")
        if (template.requiresCustomNote && noteNorm == null) {
            return DispositionValidation.Invalid("该原因须填写备注")
        }
        return DispositionValidation.Ok(
            reasonCode = template.code,
            note = noteNorm,
            banDays = 0
        )
    }

    fun findPostRestrictTemplate(code: String?): PostRestrictTemplate? {
        val normalized = normalizeReasonCode(code) ?: return null
        return postRestrictReasonTemplates.firstOrNull { it.code == normalized }
    }

    fun postRestrictedUntilFromDays(days: Int, nowMs: Long): Long {
        if (days <= 0) return 0L
        return nowMs + days.toLong() * 86_400_000L
    }

    /**
     * @param durationDays 0 = clear post restriction; >0 temporary restrict length in whole days.
     */
    fun validatePostRestrict(
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

    fun auditPostRestrictDetail(
        postRestrictedUntil: Long,
        reasonCode: String,
        note: String?
    ): String {
        val notePart = note?.let { "; note=$it" }.orEmpty()
        return "postRestrictedUntil=$postRestrictedUntil; reasonCode=$reasonCode$notePart"
    }

    
    fun findMessageRestrictTemplate(code: String?): MessageRestrictTemplate? {
        val normalized = normalizeReasonCode(code) ?: return null
        return messageRestrictReasonTemplates.firstOrNull { it.code == normalized }
    }

    fun messageRestrictedUntilFromDays(days: Int, nowMs: Long): Long {
        if (days <= 0) return 0L
        return nowMs + days.toLong() * 86_400_000L
    }

    fun validateMessageRestrict(
        durationDays: Int,
        reasonCode: String?,
        note: String?
    ): DispositionValidation {
        if (durationDays < 0 || durationDays > MAX_MESSAGE_RESTRICT_DAYS) {
            return DispositionValidation.Invalid("invalid message restrict duration")
        }
        val noteNorm = normalizeNote(note)
        if (durationDays == 0) {
            val code = normalizeReasonCode(reasonCode) ?: unrestrictMessagesReasonCode
            if (code != unrestrictMessagesReasonCode && findMessageRestrictTemplate(code) == null) {
                return DispositionValidation.Invalid("unknown unrestrict messages reason")
            }
            return DispositionValidation.Ok(
                reasonCode = if (code == unrestrictMessagesReasonCode) unrestrictMessagesReasonCode else code,
                note = noteNorm,
                banDays = 0
            )
        }
        val template = findMessageRestrictTemplate(reasonCode)
            ?: return DispositionValidation.Invalid("unknown message restrict reason")
        if (template.requiresCustomNote && noteNorm == null) {
            return DispositionValidation.Invalid("note required for this reason")
        }
        return DispositionValidation.Ok(
            reasonCode = template.code,
            note = noteNorm,
            banDays = durationDays
        )
    }

    fun auditMessageRestrictDetail(
        messageRestrictedUntil: Long,
        reasonCode: String,
        note: String?
    ): String {
        val notePart = note?.let { "; note=$it" }.orEmpty()
        return "messageRestrictedUntil=$messageRestrictedUntil; reasonCode=$reasonCode$notePart"
    }

    sealed class DispositionValidation {
        data class Ok(val reasonCode: String, val note: String?, val banDays: Int) : DispositionValidation()
        data class Invalid(val message: String) : DispositionValidation()
    }
}
