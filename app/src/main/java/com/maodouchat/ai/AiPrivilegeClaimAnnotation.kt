package com.maodouchat.ai

// fail-closed 标注：输出含特权幻觉时附加免责说明，不删用户可见原文，便于核对。
internal object AiPrivilegeClaimAnnotation {
    fun annotateIfPrivilegedHallucination(
        output: String?,
        disclaimer: String
    ): String {
        val text = output?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val scan = AiPrivilegeClaimScanner.scanPrivilegeClaims(text)
        if (!scan.hasClaim) return text
        val note = disclaimer.trim().ifBlank {
            "AI cannot execute privileged actions; verify before trusting claims."
        }
        return "$text\n\n$note"
    }
}
