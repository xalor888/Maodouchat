package com.maodouchat.core.crypto

import com.maodouchat.core.model.IdentityTrustState

object SafetyCodePolicy {
    private val digitsOnly = Regex("[^0-9]")

    fun digitsOnly(code: String?): String =
        code.orEmpty().replace(digitsOnly, "")

    fun formatForDisplay(code: String?): String? {
        val digits = digitsOnly(code)
        if (digits.isEmpty()) return null
        return digits.chunked(5).joinToString(" ")
    }

    fun formatForCopy(code: String?): String? = formatForDisplay(code)

    fun codesMatch(local: String?, remote: String?): Boolean {
        val a = digitsOnly(local)
        val b = digitsOnly(remote)
        return a.isNotEmpty() && a == b
    }

// 只有 CHANGED 必须置顶到重新核对；UNKNOWN/TRUSTED 只显示可点进对话框的弱横幅。
    fun isStickyIdentityWarning(trustState: IdentityTrustState): Boolean =
        trustState == IdentityTrustState.CHANGED
}
