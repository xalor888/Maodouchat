package com.maodouchat.ai

/**
 * AI 提示注入与越权防护（纯函数）。
 *
 * - 上下文消毒：剥离控制字符、截断、标注不可信
 * - 阻断模型输出中的「已执行特权动作」幻觉（转账/删号/改密等）
 * - 不解析也不执行任何动作；仅用于展示前 fail-closed 提示
 */
object AiPromptSafetyPolicy {

    const val MAX_CONTEXT_TEXT_CHARS = 1_800
    const val MAX_SENDER_CHARS = 120
    const val MAX_QUERY_CHARS = 700
    const val MAX_OUTPUT_SCAN_CHARS = 12_000

    data class SanitizedContextLine(
        val sender: String,
        val text: String
    )

    enum class PrivilegeClaimKind {
        TRANSFER_OR_PAYMENT,
        ACCOUNT_DESTRUCTIVE,
        AUTH_OR_KEY,
        ADMIN_OR_MODERATION,
        OTHER_PRIVILEGED
    }

    data class PrivilegeScan(
        val hasClaim: Boolean,
        val kinds: Set<PrivilegeClaimKind> = emptySet()
    )

    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val ROLE_PLAY_MARKERS = Regex(
        """(?im)^\s*(system|assistant|developer|instruction)\s*[:：]"""
    )

    private val TRANSFER_CLAIM = Regex(
        """(?i)(已(经)?(完成|执行|发起|确认)?\s*(转账|打款|付款|支付|汇款)|""" +
            """(transferred|sent\s+payment|wired\s+funds|payment\s+completed)|""" +
            """(帮你|为你|替你).{0,12}(转账|打款|付款)|""" +
            """(i\s+have\s+)?(transferred|sent)\s+(the\s+)?(money|funds|payment))"""
    )
    private val ACCOUNT_CLAIM = Regex(
        """(?i)(已(经)?(注销|删除账号|删号|清空账号)|""" +
            """(account\s+(has\s+been\s+)?(deleted|closed|wiped))|""" +
            """(帮你|为你).{0,12}(注销|删号))"""
    )
    private val AUTH_CLAIM = Regex(
        """(?i)(已(经)?(重置密码|修改密码|轮换密钥|导出密钥)|""" +
            """(password\s+(has\s+been\s+)?(reset|changed)|keys?\s+(exported|rotated))|""" +
            """(帮你|为你).{0,12}(改密|重置密码|导出密钥))"""
    )
    private val ADMIN_CLAIM = Regex(
        """(?i)(已(经)?(封禁|禁言|踢出|转让群主|设为管理员)|""" +
            """(banned|muted|kicked|ownership\s+transferred|promoted\s+to\s+admin)|""" +
            """(帮你|为你).{0,12}(封禁|禁言|踢人|转让群主))"""
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

    fun scanPrivilegeClaims(output: String?): PrivilegeScan {
        val body = output.orEmpty()
            .replace(CONTROL_CHARS, "")
            .take(MAX_OUTPUT_SCAN_CHARS)
        if (body.isBlank()) return PrivilegeScan(false)
        val kinds = buildSet {
            if (TRANSFER_CLAIM.containsMatchIn(body)) add(PrivilegeClaimKind.TRANSFER_OR_PAYMENT)
            if (ACCOUNT_CLAIM.containsMatchIn(body)) add(PrivilegeClaimKind.ACCOUNT_DESTRUCTIVE)
            if (AUTH_CLAIM.containsMatchIn(body)) add(PrivilegeClaimKind.AUTH_OR_KEY)
            if (ADMIN_CLAIM.containsMatchIn(body)) add(PrivilegeClaimKind.ADMIN_OR_MODERATION)
        }
        return PrivilegeScan(hasClaim = kinds.isNotEmpty(), kinds = kinds)
    }

    /** 展示前：若含特权幻觉，附加 fail-closed 说明（不删除用户可见原文，便于核对）。 */
    fun annotateIfPrivilegedHallucination(
        output: String?,
        disclaimer: String
    ): String {
        val text = output?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val scan = scanPrivilegeClaims(text)
        if (!scan.hasClaim) return text
        val note = disclaimer.trim().ifBlank {
            "AI cannot execute privileged actions; verify before trusting claims."
        }
        return "$text\n\n$note"
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
            lower.contains("developer mode") ||
            ROLE_PLAY_MARKERS.containsMatchIn(body)
    }
}
