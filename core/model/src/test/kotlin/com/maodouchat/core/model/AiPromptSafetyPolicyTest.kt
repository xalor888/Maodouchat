package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AiPromptSafetyPolicyTest {

    @Test
    fun `facade delegates max constants`() {
        assertEquals(
            AiPromptSanitization.MAX_CONTEXT_TEXT_CHARS,
            AiPromptSafetyPolicy.MAX_CONTEXT_TEXT_CHARS
        )
        assertEquals(
            AiPrivilegeClaimScan.MAX_OUTPUT_SCAN_CHARS,
            AiPromptSafetyPolicy.MAX_OUTPUT_SCAN_CHARS
        )
    }

    @Test
    fun `sanitizeSender null yields default user`() {
        assertEquals("user", AiPromptSafetyPolicy.sanitizeSender(null))
    }

    @Test
    fun `empty output has no privilege claim`() {
        assertFalse(AiPromptSafetyPolicy.scanPrivilegeClaims("").hasClaim)
    }
}
