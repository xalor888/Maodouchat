package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiProfileTopTermsPolicyTest {

    @Test
    fun `displayTerms truncates to eight`() {
        val terms = (1..10).map { "t$it" }
        assertEquals("t1 · t2 · t3 · t4 · t5 · t6 · t7 · t8",
            AiProfileTopTermsPolicy.displayTerms(terms, " · "))
    }

    @Test
    fun `displayTerms keeps short lists intact`() {
        assertEquals("a · b", AiProfileTopTermsPolicy.displayTerms(listOf("a", "b"), " · "))
    }

    @Test
    fun `displayTerms returns empty for empty list`() {
        assertEquals("", AiProfileTopTermsPolicy.displayTerms(emptyList(), " · "))
    }

    @Test
    fun `displayTerms honors custom separator`() {
        assertEquals("a、b", AiProfileTopTermsPolicy.displayTerms(listOf("a", "b"), "、"))
    }
}
