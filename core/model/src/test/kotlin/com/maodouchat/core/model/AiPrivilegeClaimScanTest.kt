package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPrivilegeClaimScanTest {

    @Test
    fun `transfer account auth and admin claims are classified`() {
        assertEquals(
            setOf(PrivilegeClaimKind.TRANSFER_OR_PAYMENT),
            AiPrivilegeClaimScan.scanPrivilegeClaims("已经完成转账").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.ACCOUNT_DESTRUCTIVE),
            AiPrivilegeClaimScan.scanPrivilegeClaims("已经注销").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.AUTH_OR_KEY),
            AiPrivilegeClaimScan.scanPrivilegeClaims("已经重置密码").kinds
        )
        assertEquals(
            setOf(PrivilegeClaimKind.ADMIN_OR_MODERATION),
            AiPrivilegeClaimScan.scanPrivilegeClaims("已经封禁").kinds
        )
    }

    @Test
    fun `blank and ordinary text have no claim`() {
        assertFalse(AiPrivilegeClaimScan.scanPrivilegeClaims(null).hasClaim)
        assertFalse(AiPrivilegeClaimScan.scanPrivilegeClaims("   ").hasClaim)
        assertFalse(AiPrivilegeClaimScan.scanPrivilegeClaims("明天开会讨论系统升级").hasClaim)
    }

    @Test
    fun `annotation appends disclaimer only for privileged claims`() {
        val clean = "今天天气不错"
        assertEquals(clean, AiPrivilegeClaimScan.annotateIfPrivilegedHallucination(clean, "请核实"))
        assertEquals(
            "已经完成转账\n\n请核实",
            AiPrivilegeClaimScan.annotateIfPrivilegedHallucination("已经完成转账", "请核实")
        )
        assertEquals(12_000, AiPrivilegeClaimScan.MAX_OUTPUT_SCAN_CHARS)
    }
}
