package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalSearchQueryPolicyTest {

    @Test
    fun `query truncates at 400`() {
        assertEquals("x".repeat(400), GlobalSearchQueryPolicy.filterQuery("x".repeat(401)))
    }

    @Test
    fun `recent query trims and truncates at 100`() {
        assertEquals(
            "x".repeat(100),
            GlobalSearchQueryPolicy.normalizeRecentQuery("  " + "x".repeat(101) + "  "),
        )
    }

    @Test
    fun `blank recent query stays blank`() {
        assertEquals("", GlobalSearchQueryPolicy.normalizeRecentQuery("   "))
    }

    @Test
    fun `chat ids dedupe and take 32`() {
        val ids = (1..40).map { "c${it % 35}" }
        val out = GlobalSearchQueryPolicy.limitChatIds(ids)
        assertEquals(32, out.size)
        assertEquals(out.distinct(), out)
    }

    @Test
    fun `documents take 80`() {
        assertEquals(80, GlobalSearchQueryPolicy.limitDocuments((1..81).toList()).size)
    }

    @Test
    fun `hits take 32`() {
        assertEquals(32, GlobalSearchQueryPolicy.limitHits((1..33).toList()).size)
    }
}
