package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiPrivilegeClaimScan
import com.maodouchat.core.model.AiPromptSanitization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
