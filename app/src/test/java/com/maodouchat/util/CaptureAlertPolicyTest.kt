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

    @Test
    fun `kind is truncated to 24 chars on the wire`() {
        val kind = "k".repeat(50)
        val alert = CaptureAlertPolicy.format("Alice", kind)
        val parsed = CaptureAlertPolicy.parse(alert)
        assertEquals("k".repeat(24), parsed?.first)
        assertFalse(alert.contains("k".repeat(50)))
    }

    @Test
    fun `label is trimmed then truncated to 32 chars`() {
        val alert = CaptureAlertPolicy.format("  " + "x".repeat(100) + "  ", "screenshot")
        val parsed = CaptureAlertPolicy.parse(alert)
        assertEquals("x".repeat(32) + " took a screenshot (privacy alert)", parsed?.second)
    }

    @Test
    fun `unicode labels round trip`() {
        val alert = CaptureAlertPolicy.format("张三 🚀", "screenrecord")
        val parsed = CaptureAlertPolicy.parse(alert)
        assertEquals("screenrecord", parsed?.first)
        assertTrue(parsed?.second?.contains("张三 🚀") == true)
    }

    @Test
    fun `bare prefix parses to default screenshot kind`() {
        val parsed = CaptureAlertPolicy.parse(CaptureAlertPolicy.PREFIX)
        assertEquals("screenshot", parsed?.first)
        assertEquals("", parsed?.second)
    }

    @Test
    fun `format is deterministic and kinds produce distinct wire`() {
        assertEquals(
            CaptureAlertPolicy.format("Alice", "screenshot"),
            CaptureAlertPolicy.format("Alice", "screenshot"),
        )
        assertFalse(
            CaptureAlertPolicy.format("Alice", "screenshot") ==
                CaptureAlertPolicy.format("Alice", "screenrecord"),
        )
    }

    @Test
    fun `kind with surrounding whitespace is trimmed`() {
        val alert = CaptureAlertPolicy.format("Alice", "  screenrecord  ")
        val parsed = CaptureAlertPolicy.parse(alert)
        assertEquals("screenrecord", parsed?.first)
        assertTrue(alert.contains("screenrecord|"))
    }

    @Test
    fun `exactly 32 char label is kept whole`() {
        val label = "x".repeat(32)
        val parsed = CaptureAlertPolicy.parse(CaptureAlertPolicy.format(label, "screenshot"))
        assertEquals("$label took a screenshot (privacy alert)", parsed?.second)
    }

    @Test
    fun `parse without pipe returns body as message`() {
        val parsed = CaptureAlertPolicy.parse(CaptureAlertPolicy.PREFIX + "screenshot")
        assertEquals("screenshot", parsed?.first)
        assertEquals("screenshot", parsed?.second)
    }

    @Test
    fun `parse of kind plus trailing pipe yields empty message`() {
        val parsed = CaptureAlertPolicy.parse(CaptureAlertPolicy.PREFIX + "screenshot|")
        assertEquals("screenshot", parsed?.first)
        assertEquals("", parsed?.second)
    }

    @Test
    fun `default kind applies and prefix check is case sensitive`() {
        val parsed = CaptureAlertPolicy.parse(CaptureAlertPolicy.format("Alice"))
        assertEquals("screenshot", parsed?.first)
        assertNull(CaptureAlertPolicy.parse("capture_alert:x"))
        assertFalse(CaptureAlertPolicy.isCaptureAlert("capture_alert:x"))
        assertTrue(CaptureAlertPolicy.isCaptureAlert(CaptureAlertPolicy.PREFIX))
    }


    @Test
    fun `format with blank label uses peer and exact wire form`() {
        assertEquals(
            "CAPTURE_ALERT:screenshot|peer took a screenshot (privacy alert)",
            CaptureAlertPolicy.format("   "),
        )
    }

    @Test
    fun `parse keeps extra pipes inside the message`() {
        val parsed = CaptureAlertPolicy.parse(CaptureAlertPolicy.PREFIX + "screenshot|a|b")
        assertEquals("screenshot", parsed?.first)
        assertEquals("a|b", parsed?.second)
    }

    @Test
    fun `kind containing a pipe splits at the first pipe on parse`() {
        val alert = CaptureAlertPolicy.format("Alice", "a|b")
        val parsed = CaptureAlertPolicy.parse(alert)
        assertEquals("a", parsed?.first)
    }

    @Test
    fun `label of 33 chars truncates to 32`() {
        val parsed = CaptureAlertPolicy.parse(CaptureAlertPolicy.format("x".repeat(33), "screenshot"))
        assertEquals("x".repeat(32) + " took a screenshot (privacy alert)", parsed?.second)
    }

    @Test
    fun `kind boundary 24 kept whole and 25 truncated`() {
        val kept = CaptureAlertPolicy.parse(CaptureAlertPolicy.format("Alice", "k".repeat(24)))
        assertEquals("k".repeat(24), kept?.first)
        val cut = CaptureAlertPolicy.parse(CaptureAlertPolicy.format("Alice", "k".repeat(25)))
        assertEquals("k".repeat(24), cut?.first)
    }

}
