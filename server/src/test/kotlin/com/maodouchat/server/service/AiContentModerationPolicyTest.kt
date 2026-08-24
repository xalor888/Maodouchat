package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiContentModerationPolicyTest {

    @Test
    fun `parses allow json`() {
        val d = AiContentModerationPolicy.parse("""{"verdict":"ALLOW","category":"ok","reason":""}""")
        assertEquals(AiContentModerationPolicy.Verdict.ALLOW, d.verdict)
        assertFalse(d.needsReview)
    }

    @Test
    fun `parses review even with markdown fences`() {
        val d = AiContentModerationPolicy.parse(
            """
            ```json
            {"verdict":"REVIEW","category":"spam","reason":"引流加微信"}
            ```
            """.trimIndent()
        )
        assertEquals(AiContentModerationPolicy.Verdict.REVIEW, d.verdict)
        assertEquals("WARN_MOD", d.action)
        assertEquals("spam", d.category)
        assertTrue(d.needsReview)
        assertTrue(d.reason.contains("微信"))
    }

    @Test
    fun `block maps to hold not silent drop`() {
        val d = AiContentModerationPolicy.parse(
            """{"verdict":"BLOCK","category":"scam","reason":"钓鱼收款"}"""
        )
        assertEquals(AiContentModerationPolicy.Verdict.BLOCK, d.verdict)
        assertEquals("AUTO_HOLD", d.action)
        assertTrue(d.needsReview)
    }

    @Test
    fun `garbage becomes allow fail-open`() {
        val d = AiContentModerationPolicy.parse("I refuse to classify this")
        assertEquals(AiContentModerationPolicy.Verdict.ALLOW, d.verdict)
        assertFalse(d.needsReview)
    }
}
