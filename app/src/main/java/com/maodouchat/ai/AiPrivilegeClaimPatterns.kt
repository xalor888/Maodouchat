package com.maodouchat.ai

// 特权幻觉的四类声称模式 + 控制字符：展示前 fail-closed 扫描用，pattern 逐字搬入。
internal object AiPrivilegeClaimPatterns {
    const val MAX_OUTPUT_SCAN_CHARS = 12_000

    val TRANSFER_CLAIM = Regex(
        """(?i)(已(经)?(完成|执行|发起|确认)?\s*(转账|打款|付款|支付|汇款)|""" +
            """(transferred|sent\s+payment|wired\s+funds|payment\s+completed)|""" +
            """(帮你|为你|替你).{0,12}(转账|打款|付款)|""" +
            """(i\s+have\s+)?(transferred|sent)\s+(the\s+)?(money|funds|payment))"""
    )
    val ACCOUNT_CLAIM = Regex(
        """(?i)(已(经)?(注销|删除账号|删号|清空账号)|""" +
            """(account\s+(has\s+been\s+)?(deleted|closed|wiped))|""" +
            """(帮你|为你).{0,12}(注销|删号))"""
    )
    val AUTH_CLAIM = Regex(
        """(?i)(已(经)?(重置密码|修改密码|轮换密钥|导出密钥)|""" +
            """(password\s+(has\s+been\s+)?(reset|changed)|keys?\s+(exported|rotated))|""" +
            """(帮你|为你).{0,12}(改密|重置密码|导出密钥))"""
    )
    val ADMIN_CLAIM = Regex(
        """(?i)(已(经)?(封禁|禁言|踢出|转让群主|设为管理员)|""" +
            """(banned|muted|kicked|ownership\s+transferred|promoted\s+to\s+admin)|""" +
            """(帮你|为你).{0,12}(封禁|禁言|踢人|转让群主))"""
    )
    val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
}
