package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TotpResponsePolicyTest {

    @Test
    fun `enabled true parses to true`() {
        assertTrue(TotpResponsePolicy.parseTotpEnabled("""{"enabled":true}"""))
    }

    @Test
    fun `enabled false missing key and malformed body parse to false`() {
        assertFalse(TotpResponsePolicy.parseTotpEnabled("""{"enabled":false}"""))
        assertFalse(TotpResponsePolicy.parseTotpEnabled("""{"other":1}"""))
        assertFalse(TotpResponsePolicy.parseTotpEnabled("not json"))
        assertFalse(TotpResponsePolicy.parseTotpEnabled(""))
    }

    @Test
    fun `setup response parses secret url and codes`() {
        val ready = TotpResponsePolicy.parseTotpSetupResponse(
            """{"secret":"ABC","otpauthUrl":"otpauth://totp/x","backupCodes":["c1","c2"]}"""
        )
        assertEquals("ABC", ready.secret)
        assertEquals("otpauth://totp/x", ready.otpauthUrl)
        assertEquals(listOf("c1", "c2"), ready.recoveryCodes)
    }

    @Test
    fun `setup response without optional fields uses defaults`() {
        val ready = TotpResponsePolicy.parseTotpSetupResponse("""{"secret":"ABC"}""")
        assertEquals("ABC", ready.secret)
        assertNull(ready.otpauthUrl)
        assertEquals(emptyList(), ready.recoveryCodes)
    }

    @Test
    fun `setup response without secret throws`() {
        assertFailsWith<IllegalArgumentException> {
            TotpResponsePolicy.parseTotpSetupResponse("""{"backupCodes":[]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            TotpResponsePolicy.parseTotpSetupResponse("""{"secret":"  "}""")
        }
    }

    @Test
    fun `backup codes filter blanks and tolerate malformed body`() {
        assertEquals(
            listOf("a", "b"),
            TotpResponsePolicy.parseBackupCodes("""{"backupCodes":["a","","b"]}""")
        )
        assertEquals(emptyList(), TotpResponsePolicy.parseBackupCodes("""{"other":[]}"""))
        assertEquals(emptyList(), TotpResponsePolicy.parseBackupCodes("not json"))
    }
}
