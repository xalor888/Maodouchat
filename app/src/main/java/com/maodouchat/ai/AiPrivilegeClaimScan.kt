package com.maodouchat.ai

/** 特权幻觉扫描门面：阻断模型输出中「已执行特权动作」的幻觉（转账/删号/改密等），展示前 fail-closed。 */
object AiPrivilegeClaimScan {

    const val MAX_OUTPUT_SCAN_CHARS = AiPrivilegeClaimPatterns.MAX_OUTPUT_SCAN_CHARS

    fun scanPrivilegeClaims(output: String?): PrivilegeScan =
        AiPrivilegeClaimScanner.scanPrivilegeClaims(output)

    fun annotateIfPrivilegedHallucination(
        output: String?,
        disclaimer: String
    ): String =
        AiPrivilegeClaimAnnotation.annotateIfPrivilegedHallucination(output, disclaimer)
}
