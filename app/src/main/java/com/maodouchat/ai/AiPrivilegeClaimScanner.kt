package com.maodouchat.ai

import com.maodouchat.ai.AiPrivilegeClaimScan.PrivilegeClaimKind
import com.maodouchat.ai.AiPrivilegeClaimScan.PrivilegeScan

// 特权幻觉扫描：把模型输出里「已执行特权动作」的声称（转账/删号/改密/管理动作）分类出来，展示前 fail-closed。原逻辑逐字搬入。
internal object AiPrivilegeClaimScanner {
    fun scanPrivilegeClaims(output: String?): PrivilegeScan {
        val body = output.orEmpty()
            .replace(AiPrivilegeClaimPatterns.CONTROL_CHARS, "")
            .take(AiPrivilegeClaimPatterns.MAX_OUTPUT_SCAN_CHARS)
        if (body.isBlank()) return PrivilegeScan(false)
        val kinds = buildSet {
            if (AiPrivilegeClaimPatterns.TRANSFER_CLAIM.containsMatchIn(body)) add(PrivilegeClaimKind.TRANSFER_OR_PAYMENT)
            if (AiPrivilegeClaimPatterns.ACCOUNT_CLAIM.containsMatchIn(body)) add(PrivilegeClaimKind.ACCOUNT_DESTRUCTIVE)
            if (AiPrivilegeClaimPatterns.AUTH_CLAIM.containsMatchIn(body)) add(PrivilegeClaimKind.AUTH_OR_KEY)
            if (AiPrivilegeClaimPatterns.ADMIN_CLAIM.containsMatchIn(body)) add(PrivilegeClaimKind.ADMIN_OR_MODERATION)
        }
        return PrivilegeScan(hasClaim = kinds.isNotEmpty(), kinds = kinds)
    }
}
