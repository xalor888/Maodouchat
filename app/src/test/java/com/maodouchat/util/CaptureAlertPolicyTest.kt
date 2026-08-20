package com.maodouchat.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureAlertPolicyTest {

    @Test
    fun `format produces prefixed alert with kind and who`() {
        val alert = CaptureAlertPolicy.format("Alice", "screenshot")
        assertTrue(alert.startsWith(CaptureAlertPolicy.PREFIX))
        assertTrue(alert.contains("Alice"))
        assertTrue(alert.contains("screenshot"))
    }

    @Test
    fun `parse round trips formatted alert`() {
        val alert = CaptureAlertPolicy.format("Bob", "screenrecord")
        val parsed = CaptureAlertPolicy.parse(alert)
        assertEquals("screenrecord", parsed?.first)
        assertTrue(parsed?.second?.contains("Bob") == true)
    }

    @Test
    fun `blank label falls back to peer and blank kind to screenshot`() {
        val alert = CaptureAlertPolicy.format("   ", "")
        val parsed = CaptureAlertPolicy.parse(alert)
        assertEquals("screenshot", parsed?.first)
        assertTrue(parsed?.second?.contains("peer") == true)
    }

    @Test
    fun `long labels are truncated for wire size`() {
        val longName = "x".repeat(100)
        val alert = CaptureAlertPolicy.format(longName, "screenshot")
        // label 截断到 32，wire 上不应出现完整 100 字符
        assertFalse(alert.contains(longName))
    }

    @Test
    fun `parse returns null for non alert content`() {
        assertNull(CaptureAlertPolicy.parse("normal message"))
        assertNull(CaptureAlertPolicy.parse(""))
        assertFalse(CaptureAlertPolicy.isCaptureAlert("CAPTURE_ALERT without colon prefix"))
        assertTrue(CaptureAlertPolicy.isCaptureAlert(CaptureAlertPolicy.PREFIX + "x"))
    }
}
