package com.maodouchat.domain.messaging

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class MarkdownDetectionPolicyTest {

    @Test
    fun `looks like markdown basics`() {
        assertTrue(MarkdownDetectionPolicy.looksLikeMarkdown("# title"))
        assertTrue(MarkdownDetectionPolicy.looksLikeMarkdown("**bold**"))
        assertTrue(MarkdownDetectionPolicy.looksLikeMarkdown("- item"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("plain text"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown(""))
    }
}
