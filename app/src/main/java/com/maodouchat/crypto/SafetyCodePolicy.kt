package com.maodouchat.crypto

/**
 * Safety-number presentation helpers (M2-9).
 * Codes are already produced as 5-digit groups separated by spaces; this policy
 * normalizes display/copy and decides when identity-change warnings stay sticky.
 */
object SafetyCodePolicy {
    private val digitsOnly = Regex("[^0-9]")

    fun digitsOnly(code: String?): String =
        code.orEmpty().replace(digitsOnly, "")

    /** Normalize to groups of 5 digits separated by a single space for visual compare. */
    fun formatForDisplay(code: String?): String? {
        val digits = digitsOnly(code)
        if (digits.isEmpty()) return null
        return digits.chunked(5).joinToString(" ")
    }

    /** Clipboard form: same as display (spaces kept for human paste/compare). */
    fun formatForCopy(code: String?): String? = formatForDisplay(code)

    fun codesMatch(local: String?, remote: String?): Boolean {
        val a = digitsOnly(local)
        val b = digitsOnly(remote)
        return a.isNotEmpty() && a == b
    }

    /**
     * CHANGED identity warnings must not be dismissed without verification.
     * UNKNOWN/TRUSTED may show a softer banner that still routes into the dialog.
     */
    fun isStickyIdentityWarning(trustState: SignalProtocol.IdentityTrustState): Boolean =
        trustState == SignalProtocol.IdentityTrustState.CHANGED
}
