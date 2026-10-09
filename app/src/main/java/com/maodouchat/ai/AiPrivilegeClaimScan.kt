package com.maodouchat.ai

/** 特权幻觉扫描簇：阻断模型输出中「已执行特权动作」的幻觉（转账/删号/改密等），展示前 fail-closed。 */
object AiPrivilegeClaimScan {

    const val MAX_OUTPUT_SCAN_CHARS = 12_000

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

    private val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
}
